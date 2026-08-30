package com.vmodal.sdk.examples.app1

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vmodal.sdk.ApiError
import com.vmodal.sdk.Client
import com.vmodal.sdk.MutableApiKeyProvider
import com.vmodal.sdk.PUBLIC_GATEWAY_URL
import com.vmodal.sdk.SdkError
import com.vmodal.sdk.UploadSource
import com.vmodal.sdk.VideoUploadEvent
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ResultFrame(
    val id: String,
    val model: Any,
    val caption: String,
)

enum class Busy { NONE, CONNECT, UPLOAD, INDEX, STATUS, SEARCH }

data class KitchenUiState(
    val apiKey: String = "",
    val connectedUser: String = "",
    val collection: String = "app1_kitchen",
    val stream: String = "main",
    val query: String = "sugar",
    val videoName: String = "",
    val videoOrigin: String = "",
    val uploadProgress: Int = 0,
    val uploaded: Boolean = false,
    val indexStatus: String = "",
    val indexReady: Boolean = false,
    val busy: Busy = Busy.NONE,
    val status: String = "Paste your runtime API key, then press Connect.",
    val error: String = "",
    val searched: Boolean = false,
    val frames: List<ResultFrame> = emptyList(),
    val searchInfo: String = "",
)

class KitchenViewModel private constructor(
    private val appContext: Context,
) : ViewModel() {

    private val mutable = MutableStateFlow(KitchenUiState())
    val state: StateFlow<KitchenUiState> = mutable.asStateFlow()

    private var client: Client? = null
    private var source: UploadSource? = null
    private var indexJobId: String = ""
    private var job: Job? = null

    fun onApiKey(value: String) = mutable.update { it.copy(apiKey = value, error = "") }
    fun onCollection(value: String) = mutable.update { it.copy(collection = value) }
    fun onStream(value: String) = mutable.update { it.copy(stream = value) }
    fun onQuery(value: String) = mutable.update { it.copy(query = value) }

    fun useBundledVideo() {
        val loaded = runCatching { assetUploadSource(appContext, BUNDLED_VIDEO) }.getOrNull()
        if (loaded == null) {
            mutable.update {
                it.copy(
                    error = "No bundled $BUNDLED_VIDEO in assets. Copy a short clip to " +
                        "examples/app1/asset/$BUNDLED_VIDEO, or pick a video from the device.",
                )
            }
        } else {
            source = loaded
            mutable.update {
                it.copy(
                    videoName = loaded.fileName,
                    videoOrigin = "bundled",
                    uploaded = false,
                    uploadProgress = 0,
                    indexReady = false,
                    indexStatus = "",
                    error = "",
                    status = "Bundled clip ready: ${loaded.fileName}. Upload it next.",
                )
            }
        }
    }

    fun usePickedVideo(uri: Uri) {
        val loaded = runCatching { contentUriUploadSource(appContext, uri) }
        loaded.onSuccess { src ->
            source = src
            mutable.update {
                it.copy(
                    videoName = src.fileName,
                    videoOrigin = "picked on device",
                    uploaded = false,
                    uploadProgress = 0,
                    indexReady = false,
                    indexStatus = "",
                    error = "",
                    status = "Video ready: ${src.fileName}. Upload it next.",
                )
            }
        }
        loaded.onFailure { e ->
            mutable.update { it.copy(error = e.message ?: "Could not read the selected video.") }
        }
    }

    fun connect() = launch(Busy.CONNECT) {
        val key = mutable.value.apiKey.trim()
        check(key.isNotBlank()) { "A runtime API key is required." }
        val gateway = Client(baseUrl = PUBLIC_GATEWAY_URL, apiKeyProvider = MutableApiKeyProvider(key))
        val me = gateway.coroutines().auth.me()
        val userId = me.userId ?: error("auth/me returned no user id.")
        client = Client(
            gateway.cfg.copy(
                userId = userId,
                tenantId = me.tenantId.orEmpty(),
                email = me.email.orEmpty(),
            ),
        )
        mutable.update {
            it.copy(
                connectedUser = me.type,
                status = "Connected as ${me.type}. Pick a video, upload it, create the index, search.",
            )
        }
    }

    fun upload() = launch(Busy.UPLOAD) {
        val sdk = requireClient()
        val src = source ?: error("Pick a video first: bundled clip or from the device.")
        val scope = mutable.value
        var name = ""
        sdk.coroutines().collections.videoUploadEvents(
            source = src,
            collectionName = scope.collection.trim(),
            subCollectionName = scope.stream.trim(),
        ).collect { event ->
            when (event) {
                is VideoUploadEvent.Progress ->
                    mutable.update { it.copy(uploadProgress = event.progress.percent) }
                is VideoUploadEvent.Completed -> {
                    check(event.response.uploaded) { "Upload did not complete: ${event.response.raw}" }
                    name = event.response.fileName.ifBlank { src.fileName }
                }
            }
        }
        indexJobId = ""
        mutable.update {
            it.copy(
                uploadProgress = 100,
                uploaded = true,
                indexReady = false,
                indexStatus = "",
                frames = emptyList(),
                searched = false,
                status = "Uploaded $name. Create its index next.",
            )
        }
    }

    fun createIndex() = launch(Busy.INDEX) {
        val sdk = requireClient()
        val scope = mutable.value
        val result = sdk.coroutines().indexes.createIndex(
            mode = "vid_file",
            groupName = scope.collection.trim(),
            streamName = scope.stream.trim(),
            version = "new_version",
            reProcess = true,
        )
        indexJobId = result.jobId
        mutable.update {
            it.copy(
                indexStatus = result.status.ifBlank { "queued" },
                indexReady = false,
                status = "Index job created. Press index status until it is ready.",
            )
        }
    }

    fun refreshIndex() = launch(Busy.STATUS) {
        val sdk = requireClient()
        check(indexJobId.isNotBlank()) { "Create the index first." }
        val result = sdk.coroutines().indexes.indexStatus(indexJobId)
        val status = result.status.ifBlank { "unknown" }
        val ready = status.trim().lowercase(Locale.US) in setOf(
            "success", "succeeded", "done", "completed", "ok",
        )
        mutable.update {
            it.copy(
                indexStatus = status,
                indexReady = ready,
                status = if (ready) {
                    "Index is ready. Search the collection next."
                } else {
                    "Index status: $status. Refresh again in a few seconds."
                },
            )
        }
    }

    fun search() = launch(Busy.SEARCH) {
        val sdk = requireClient()
        val scope = mutable.value
        val query = scope.query.trim()
        check(query.isNotBlank()) { "Type a search query first." }
        val collection = scope.collection.trim()
        val stream = scope.stream.trim()

        val groups = sdk.coroutines().collections.listGroups("vid_file")
        val group = groups.data.firstOrNull { it.mode == "vid_file" && it.groupName.trim() == collection }
            ?: error("Collection $collection is not available for this key. Upload the video first.")
        val version = group.latestLancedbVersion
            ?: error("Collection $collection has no index version yet. Create the index first.")

        val response = sdk.coroutines().searches.searchVideo(
            queryText = query,
            mode = "vid_file",
            groupName = collection,
            streamName = stream,
            limit = 50,
            textEmbScoreMin = 0.0,
            imageEmbScoreMin = 0.0,
            versionLancedb = version,
        )

        val candidates = searchCandidates(response.data, collection, stream)
        var urlCount = 0
        var resolvedCount = 0
        val images = if (candidates.isEmpty()) {
            emptyList()
        } else {
            val urlRecords = sdk.coroutines().images.getUrlBulk(candidates.map { it.record }).records
            urlCount = urlRecords.size
            val resolved = searchImages(candidates, urlRecords)
            resolvedCount = resolved.size
            if (resolved.isEmpty()) {
                resolved
            } else {
                val content = sdk.coroutines().images
                    .getImageBulkFromUrls(resolved.map { it.url }).records
                searchImageBytes(resolved, content)
            }
        }

        val frames = withContext(Dispatchers.Default) {
            images.map { img ->
                val bitmap = img.bytes.takeIf { it.isNotEmpty() }?.let {
                    BitmapFactory.decodeByteArray(it, 0, it.size)
                }
                ResultFrame(
                    id = img.id,
                    model = bitmap ?: img.url,
                    caption = listOfNotNull(
                        img.score.takeIf { it.isNotBlank() },
                        img.filename.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                )
            }
        }

        val firstKeys = (response.data.firstOrNull() as? Map<*, *>)?.keys
            ?.take(14)
            ?.joinToString(",") { it.toString() }
            .orEmpty()
        val debug = "rows=${response.data.size} cand=${candidates.size} urls=$urlCount " +
            "imgs=$resolvedCount" + (if (firstKeys.isEmpty()) "" else " keys=[$firstKeys]")

        mutable.update {
            it.copy(
                searched = true,
                frames = frames,
                searchInfo = "${frames.size} frames · ${response.cntActual} matches · " +
                    "%.0f ms".format(Locale.US, response.executionTimeMs) + " · $debug",
                status = if (frames.isEmpty() && response.cntActual > 0) {
                    "Server matched ${response.cntActual} frames but returned no reusable image " +
                        "records for $collection/$stream. Debug: $debug — try re-running the index, " +
                        "or send this line to the SDK team."
                } else {
                    "Search done: $query in $collection/$stream."
                },
            )
        }
    }

    override fun onCleared() {
        job?.cancel()
        client = null
        super.onCleared()
    }

    private fun requireClient(): Client = client ?: error("Connect with your API key first.")

    private fun launch(busy: Busy, block: suspend () -> Unit) {
        job?.cancel()
        job = viewModelScope.launch {
            mutable.update { it.copy(busy = busy, error = "") }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(error = friendly(e)) }
            } finally {
                mutable.update { current ->
                    if (current.busy == busy) current.copy(busy = Busy.NONE) else current
                }
            }
        }
    }

    private fun friendly(error: Exception): String {
        if (error is ApiError && error.statusCode == 404) {
            val body = error.body.toString().lowercase(Locale.US)
            if ("missing lancedb" in body || "missing index" in body) {
                return "No searchable index yet. Upload the video and create its index first."
            }
        }
        return if (error is SdkError) error.toString() else error.message ?: "Something went wrong."
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    KitchenViewModel(context.applicationContext) as T
            }
    }
}

/* ---- result mapping: same logic as examples/03_fullapp ---- */

internal data class FrameCandidate(
    val searchRank: Int,
    val row: Map<String, Any?>,
    val record: Map<String, Any?>,
)

internal data class FrameImage(
    val id: String,
    val url: String,
    val filename: String,
    val timestamp: String,
    val score: String,
    val bytes: ByteArray = byteArrayOf(),
)

internal fun searchCandidates(
    values: List<Any?>,
    collection: String,
    stream: String,
): List<FrameCandidate> = values.mapIndexedNotNull { rank, value ->
    val row = (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
        ?: return@mapIndexedNotNull null
    val filenameAlias = firstString(
        row,
        "filename",
        "filename_sanitized",
        "video_filename",
        "video",
        "source_path",
        "path",
    )
    val rawName = filenameAlias.ifBlank { candidateFileName(row) }
    val filename = rawName.trim().replace('\\', '/').substringAfterLast('/').trim()
    if (filename.isBlank()) return@mapIndexedNotNull null

    val record = linkedMapOf<String, Any?>(
        "mode" to "vid_file",
        "group_name" to collection.trim(),
        "modality" to "vid_img",
        "stream_name" to firstString(row, "stream", "stream_name").ifBlank { stream.trim() },
        "filename" to filename,
    )
    val stamp = timestamp13(firstString(row, "ts_unix_13digits", "ts_unix", "timestamp_ms"))
    if (stamp.isNotBlank()) record["ts_unix_13digits"] = stamp
    FrameCandidate(rank, row, record)
}

private fun candidateFileName(row: Map<String, Any?>): String {
    val title = firstString(row, "title")
    if (title.isNotBlank()) return title

    val id = firstString(row, "item_id")
    val stream = firstString(row, "stream")
    val unix = firstString(row, "ts_unix")
    if (id.isBlank() || stream.isBlank() || unix.isBlank()) return ""

    var middle = id
    if (middle.startsWith("$stream-")) {
        middle = middle.removePrefix("$stream-")
    }
    if (middle.endsWith("-$unix")) {
        middle = middle.removeSuffix("-$unix")
    }
    middle = middle.trim()
    return middle.ifEmpty { id }
}

internal fun searchImages(
    candidates: List<FrameCandidate>,
    records: List<Map<String, Any?>>,
): List<FrameImage> {
    val resolved = mutableMapOf<Int, FrameImage>()
    records.forEachIndexed { rowIndex, row ->
        val rawIndex = row["input_index"]
        val inputIndex = if (rawIndex == null) rowIndex else inputIndex(rawIndex)
        if (inputIndex == null || inputIndex !in candidates.indices || inputIndex in resolved) {
            return@forEachIndexed
        }
        if (row["found"] == false) return@forEachIndexed
        val url = row["url_pre_signed"]?.toString()?.trim().orEmpty()
        if (url.isBlank()) return@forEachIndexed

        val candidate = candidates[inputIndex]
        val filename = candidate.record["filename"]?.toString()?.trim().orEmpty()
        val timestamp = candidate.record["ts_unix_13digits"]?.toString()?.trim().orEmpty()
        resolved[inputIndex] = FrameImage(
            id = "${candidate.searchRank}-$filename-$timestamp",
            url = url,
            filename = filename,
            timestamp = timestamp,
            score = searchScore(candidate.row),
        )
    }
    return resolved.toSortedMap().values.toList()
}

internal fun searchImageBytes(
    images: List<FrameImage>,
    records: List<Map<String, Any?>>,
): List<FrameImage> {
    val content = mutableMapOf<Int, ByteArray>()
    records.forEachIndexed { rowIndex, row ->
        val rawIndex = row["input_index"]
        val inputIndex = if (rawIndex == null) rowIndex else inputIndex(rawIndex)
        if (inputIndex == null || inputIndex !in images.indices || inputIndex in content) {
            return@forEachIndexed
        }
        if (row["found"] == false) return@forEachIndexed
        val encoded = row["content_base64"]?.toString()?.trim().orEmpty()
        if (encoded.isBlank()) return@forEachIndexed
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: byteArrayOf()
        if (bytes.isNotEmpty()) content[inputIndex] = bytes
    }
    return images.mapIndexed { index, image -> image.copy(bytes = content[index] ?: byteArrayOf()) }
}

private fun firstString(row: Map<String, Any?>, vararg names: String): String =
    names.firstNotNullOfOrNull { name ->
        row[name]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }.orEmpty()

private fun timestamp13(value: String): String {
    val digits = value.filter(Char::isDigit)
    return when {
        digits.length >= 13 -> digits.take(13)
        digits.length == 10 -> "${digits}000"
        digits.isNotBlank() -> digits.padStart(13, '0')
        else -> ""
    }
}

private fun searchScore(row: Map<String, Any?>): String {
    val scoreUi = row["score_ui"]
    if (scoreUi is Number) {
        val value = scoreUi.toDouble()
        if (value.isFinite() && value in 0.0..1.0) {
            return String.format(Locale.US, "%.1f%%", value * 100)
        }
    }
    for (name in listOf("score_ui", "score", "similarity", "image_score", "text_score")) {
        val value = row[name]
        if (value is Number && !value.toDouble().isFinite()) continue
        value?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return ""
}

private fun inputIndex(value: Any?): Int? = when (value) {
    is Number -> value.toDouble().takeIf { it.isFinite() }?.toInt()
    is String -> value.trim().toIntOrNull()
    else -> null
}
