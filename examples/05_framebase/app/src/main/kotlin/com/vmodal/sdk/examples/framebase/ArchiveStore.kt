package com.vmodal.sdk.examples.framebase

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.Locale

/** Owns the non-secret archive JSON and app-private MP4 copies. */
class ArchiveStore private constructor(
    private val rootDir: File,
    private val assets: android.content.res.AssetManager?,
    private val resolver: android.content.ContentResolver?,
) {
    constructor(context: Context) : this(
        File(context.filesDir, "framebase"),
        context.assets,
        context.contentResolver,
    )

    /** A file-root constructor keeps persistence tests independent of an Activity. */
    constructor(filesDir: File) : this(filesDir, null, null)

    private val stateFile: AtomicFile
        get() = AtomicFile(File(rootDir, "archive.json"))

    init {
        rootDir.mkdirs()
    }

    @Synchronized
    fun load(): ArchiveState {
        val file = File(rootDir, "archive.json")
        if (!file.exists()) return ArchiveState()
        return try {
            val json = JSONObject(stateFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
            val clips = json.optJSONArray("clips")?.let(::decodeClips).orEmpty()
            val valid = clips.filter { clip ->
                clip.bundled || (!clip.localPath.isNullOrBlank() && File(clip.localPath).isFile)
            }
            if (valid.isEmpty()) {
                ArchiveState()
            } else {
                ArchiveState(
                    clips = valid,
                    pendingJobId = json.optString("pendingJobId", ""),
                    accountId = json.optString("accountId", ""),
                    events = decodeEvents(json.optJSONArray("events")),
                )
            }
        } catch (_: Exception) {
            ArchiveState()
        }
    }

    /** AtomicFile serializes all snapshots; a write error leaves the live work usable. */
    @Synchronized
    fun save(state: ArchiveState): Boolean {
        val snapshot = JSONObject().apply {
            put("clips", JSONArray().apply { state.clips.forEach { put(encodeClip(it)) } })
            put("pendingJobId", state.pendingJobId)
            put("accountId", state.accountId)
            put("events", JSONArray().apply { boundedEvents(state.events).forEach { put(encodeEvent(it)) } })
        }.toString()
        var output: FileOutputStream? = null
        return try {
            output = stateFile.startWrite()
            output.write(snapshot.toByteArray(Charsets.UTF_8))
            output.flush()
            stateFile.finishWrite(output)
            true
        } catch (_: Exception) {
            if (output != null) stateFile.failWrite(output)
            false
        }
    }

    fun addEvent(state: ArchiveState, title: String, detail: String, isError: Boolean = false): ArchiveState =
        state.copy(events = prependEvent(state.events, ArchiveEvent(title, detail, isError)))

    /** Copies a bundled asset only when playback/upload first needs a local file. */
    fun ensureBundledLocal(clip: ArchiveClip): ArchiveClip {
        require(clip.bundled && !clip.assetPath.isNullOrBlank()) { "Only bundled clips have assets" }
        val existing = clip.localPath?.let(::File)
        if (existing?.isFile == true) return clip
        val assetManager = assets ?: error("Asset access is unavailable")
        val assetPath = clip.assetPath ?: error("Bundled clip has no asset")
        val destination = File(mediaDir(), clip.remoteFilename)
        if (!destination.isFile) {
            assetManager.open(assetPath).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return clip.copy(localPath = destination.absolutePath)
    }

    /** Returns an app-private playable file, copying a bundled asset on demand. */
    fun localFile(clip: ArchiveClip): File {
        clip.localPath?.let(::File)?.takeIf { it.isFile }?.let { return it }
        if (!clip.bundled) throw FileNotFoundException("Recording is no longer stored on this device")
        return File(ensureBundledLocal(clip).localPath ?: error("Bundled copy failed"))
    }

    /** Validates and eagerly owns an imported MP4; the external Uri is never retained. */
    fun importVideo(uri: Uri, displayName: String? = null): ArchiveClip {
        val contentResolver = resolver ?: error("Content access is unavailable")
        val name = displayName ?: queryDisplayName(contentResolver, uri) ?: uri.lastPathSegment.orEmpty()
        val cleanName = basename(name).ifBlank { "Imported recording.mp4" }
        val mime = contentResolver.getType(uri).orEmpty().lowercase(Locale.US)
        if (!cleanName.lowercase(Locale.US).endsWith(".mp4") || (mime.isNotBlank() && mime != "video/mp4")) {
            throw IllegalArgumentException("Choose a playable MP4 smaller than 100 MB.")
        }
        val declaredSize = querySize(contentResolver, uri)
        if (declaredSize > MAX_IMPORT_BYTES) {
            throw IllegalArgumentException("Choose a playable MP4 smaller than 100 MB.")
        }

        val id = nextImportId()
        val target = File(mediaDir(), "$id.mp4")
        val partial = File(mediaDir(), "$id.partial")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_IMPORT_BYTES) {
                            throw IllegalArgumentException("Choose a playable MP4 smaller than 100 MB.")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: throw IllegalArgumentException("Choose a playable MP4 smaller than 100 MB.")
            val durationSec = mediaDuration(partial)
            if (!durationSec.isFinite() || durationSec <= 0.0) {
                throw IllegalArgumentException("Choose a playable MP4 smaller than 100 MB.")
            }
            check(partial.renameTo(target)) { "Could not store imported video" }
            return newImportedClip(id, cleanName, durationSec, target.absolutePath)
        } catch (error: Exception) {
            partial.delete()
            target.delete()
            throw error
        }
    }

    private fun decodeClips(rows: JSONArray): List<ArchiveClip> {
        val result = mutableListOf<ArchiveClip>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val id = row.optString("id").trim()
            val title = row.optString("title").trim()
            val location = row.optString("location").trim()
            val duration = row.optDouble("durationSec", Double.NaN)
            if (id.isBlank() || title.isBlank() || location.isBlank() || !duration.isFinite() || duration < 0.0) continue
            val assetPath = row.optNullableString("assetPath")
            result += ArchiveClip(
                id = id,
                title = title,
                location = location,
                durationSec = duration,
                assetPath = assetPath,
                posterAssetPath = row.optNullableString("posterAssetPath"),
                localPath = row.optNullableString("localPath"),
                uploaded = row.optBoolean("uploaded", false),
                bundled = row.optBoolean("bundled", assetPath != null),
            )
        }
        return result
    }

    private fun decodeEvents(rows: JSONArray?): List<ArchiveEvent> {
        if (rows == null) return emptyList()
        val result = mutableListOf<ArchiveEvent>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val title = row.optString("title").trim()
            val detail = row.optString("detail").trim()
            val time = row.optString("time").trim()
            if (title.isNotBlank() && detail.isNotBlank() && time.isNotBlank()) {
                result += ArchiveEvent(title, detail, row.optBoolean("isError", false), time)
            }
        }
        return boundedEvents(result)
    }

    private fun encodeClip(clip: ArchiveClip): JSONObject = JSONObject().apply {
        put("id", clip.id)
        put("title", clip.title)
        put("location", clip.location)
        put("durationSec", clip.durationSec)
        put("assetPath", clip.assetPath)
        put("posterAssetPath", clip.posterAssetPath)
        put("localPath", clip.localPath)
        put("uploaded", clip.uploaded)
        put("bundled", clip.bundled)
    }

    private fun encodeEvent(event: ArchiveEvent): JSONObject = JSONObject().apply {
        put("title", event.title)
        put("detail", event.detail)
        put("isError", event.isError)
        put("time", event.time)
    }

    private fun mediaDir(): File = File(rootDir, "media").also { it.mkdirs() }

    private fun nextImportId(): String {
        var millis = System.currentTimeMillis()
        var file = File(mediaDir(), "street_$millis.mp4")
        while (file.exists()) {
            millis++
            file = File(mediaDir(), "street_$millis.mp4")
        }
        return "street_$millis"
    }

    private fun mediaDuration(file: File): Double {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val millis = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()
                ?: Double.NaN
            millis / 1000.0
        } finally {
            retriever.release()
        }
    }

    private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun querySize(resolver: android.content.ContentResolver, uri: Uri): Long {
        return resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
        } ?: -1L
    }

    private fun basename(path: String): String = path.replace('\\', '/').substringAfterLast('/')
}

private fun JSONObject.optNullableString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
