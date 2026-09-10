package com.vmodal.sdk.examples.framebase

import com.vmodal.sdk.Client
import com.vmodal.sdk.IndexationSubmitResponse
import com.vmodal.sdk.MutableApiKeyProvider
import com.vmodal.sdk.PUBLIC_GATEWAY_URL
import com.vmodal.sdk.SignedUploadTransport
import com.vmodal.sdk.UploadSource
import com.vmodal.sdk.VmodalTransport
import com.vmodal.sdk.VideoUploadEvent
import java.net.URI
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/** Fixed remote scope owned by the Framebase example. */
const val FRAMEBASE_MODE = "vid_file"
const val FRAMEBASE_IMAGE_MODALITY = "vid_img"
const val FRAMEBASE_IMAGE_PATH = "/api/external/v1/image/get_image"
const val FOCUSED_DISTANCE = 0.85
const val LOOSE_DISTANCE = 1.5

data class FramebaseConnection(
    val accountId: String,
    val indexVersion: Int?,
)

data class FramebaseIndexResult(
    val jobId: String,
    val status: String,
)

/** Small seam used by the ViewModel and by deterministic JVM fakes. */
interface FramebaseGateway {
    val accountId: String
    val indexVersion: Int?

    suspend fun connect(): FramebaseConnection

    fun upload(source: UploadSource): Flow<VideoUploadEvent>

    suspend fun createIndex(): FramebaseIndexResult

    suspend fun indexStatus(jobId: String): FramebaseIndexResult

    /** Refreshes the fixed collection version after an index reaches a terminal success state. */
    suspend fun refreshIndexVersion(): Int? = indexVersion

    suspend fun search(query: String, maxDistance: Double = FOCUSED_DISTANCE): SearchBatch

    suspend fun close()
}

/**
 * SDK-backed Framebase gateway. The SDK remains the owner of request construction, retries,
 * authentication headers, upload streams, and response decoding.
 *
 * [transport] and [signedUploads] are injectable so JVM tests can use a fake transport without
 * making network calls. The API key exists only in [keys] memory and is cleared by [close].
 */
private data class FramebaseSdkSetup(
    val keys: MutableApiKeyProvider,
    val client: Client,
)

private fun framebaseSdkSetup(
    apiKey: String,
    baseUrl: String,
    timeoutMillis: Int,
    transport: VmodalTransport?,
    signedUploads: SignedUploadTransport?,
): FramebaseSdkSetup {
    val keys = MutableApiKeyProvider(apiKey)
    return FramebaseSdkSetup(
        keys,
        Client(
            baseUrl = baseUrl,
            timeoutMillis = timeoutMillis,
            transport = transport,
            signedUploads = signedUploads,
            apiKeyProvider = keys,
        ),
    )
}

class SdkFramebaseGateway private constructor(
    setup: FramebaseSdkSetup,
) : FramebaseGateway {
    val keys: MutableApiKeyProvider = setup.keys
    private val client: Client = setup.client

    constructor(keys: MutableApiKeyProvider, client: Client) : this(FramebaseSdkSetup(keys, client))

    constructor(
        apiKey: String,
        baseUrl: String = PUBLIC_GATEWAY_URL,
        timeoutMillis: Int = 60_000,
        transport: VmodalTransport? = null,
        signedUploads: SignedUploadTransport? = null,
    ) : this(framebaseSdkSetup(apiKey, baseUrl, timeoutMillis, transport, signedUploads))

    override var accountId: String = ""
        private set
    override var indexVersion: Int? = null
        private set

    private val coroutines = client.coroutines()
    private var closed = false

    override suspend fun connect(): FramebaseConnection {
        checkOpen()
        val profile = coroutines.auth.me()
        val userId = profile.userId?.trim().orEmpty()
        require(userId.isNotBlank()) { "auth/me returned no user_id" }
        accountId = userId
        indexVersion = findLatestVersion(coroutines.collections.listGroups(FRAMEBASE_MODE))
        return FramebaseConnection(accountId, indexVersion)
    }

    override fun upload(source: UploadSource): Flow<VideoUploadEvent> {
        checkOpen()
        return coroutines.collections.videoUploadEvents(
            source = source,
            collectionName = ARCHIVE_COLLECTION,
            subCollectionName = ARCHIVE_STREAM,
            mode = FRAMEBASE_MODE,
            modality = "vid_raw",
        )
    }

    override suspend fun createIndex(): FramebaseIndexResult {
        checkOpen()
        val response = coroutines.indexes.createIndex(
            mode = FRAMEBASE_MODE,
            groupName = ARCHIVE_COLLECTION,
            streamName = ARCHIVE_STREAM,
            reProcess = true,
        )
        return indexResult(response)
    }

    override suspend fun indexStatus(jobId: String): FramebaseIndexResult {
        checkOpen()
        val clean = jobId.trim()
        require(clean.isNotBlank()) { "index job id is required" }
        val response = coroutines.indexes.indexStatus(clean)
        return FramebaseIndexResult(response.jobId.ifBlank { clean }, response.status)
    }

    override suspend fun refreshIndexVersion(): Int? {
        checkOpen()
        indexVersion = findLatestVersion(coroutines.collections.listGroups(FRAMEBASE_MODE))
        return indexVersion
    }

    override suspend fun search(query: String, maxDistance: Double): SearchBatch {
        checkOpen()
        require(maxDistance.isFinite() && maxDistance >= 0.0) { "distance cutoff is invalid" }
        val version = indexVersion
        val searchStarted = System.nanoTime()
        val response = coroutines.searches.searchVideo(
            queryText = query,
            mode = FRAMEBASE_MODE,
            groupName = ARCHIVE_COLLECTION,
            streamName = ARCHIVE_STREAM,
            limit = 30,
            imageEmbScoreMin = maxDistance,
            versionLancedb = version,
        )
        val searchMs = elapsedMs(searchStarted)
        val candidates = framebaseCandidates(response.data, maxDistance)
        val imageStarted = System.nanoTime()
        val urls = linkedMapOf<Int, String>()
        if (candidates.isNotEmpty()) {
            val lookup = candidates.map(FramebaseCandidate::record)
            val urlRecords = try {
                coroutines.images.getUrlBulk(lookup).records
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
            urls.putAll(resolveImageUrls(urlRecords, candidates.size))
            if (urls.isNotEmpty()) {
                // The content endpoint indexes the locator list, not the original search rows.
                // Sort by candidate index so its input_index can never cross-associate a hit.
                val locatorIndexes = urls.keys.sorted()
                val locatorUrls = locatorIndexes.map { urls.getValue(it) }
                val contentRecords = try {
                    coroutines.images.getImageBulkFromUrls(locatorUrls).records
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    emptyList()
                }
                val bytes = resolveImageBytes(contentRecords, urls, locatorIndexes)
                return SearchBatch(
                    matches = framebaseMatches(candidates, urls, bytes),
                    total = response.cntTotal,
                    serverMs = response.executionTimeMs,
                    roundTripMs = searchMs,
                    imageMs = elapsedMs(imageStarted),
                )
            }
        }
        return SearchBatch(
            matches = framebaseMatches(candidates, urls, emptyMap()),
            total = response.cntTotal,
            serverMs = response.executionTimeMs,
            roundTripMs = searchMs,
            imageMs = elapsedMs(imageStarted),
        )
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        keys.clear()
        // Client currently has no close API; its transports are request-scoped. Clearing the
        // provider is sufficient to make all future auth attempts fail without retaining a key.
    }

    private fun checkOpen() {
        check(!closed) { "Framebase gateway is closed" }
    }
}

private fun indexResult(response: IndexationSubmitResponse): FramebaseIndexResult {
    val jobId = response.jobId.trim()
    require(jobId.isNotBlank()) { "index response did not contain a job id" }
    return FramebaseIndexResult(jobId, response.status)
}

private fun findLatestVersion(groups: com.vmodal.sdk.GroupsResponse): Int? = groups.data
    .asSequence()
    .filter { it.mode.trim() == FRAMEBASE_MODE }
    .filter { it.groupName.trim() == ARCHIVE_COLLECTION }
    .mapNotNull { it.latestLancedbVersion }
    .maxOrNull()

internal data class FramebaseCandidate(
    val row: Map<String, Any?>,
    val filename: String,
    val timestamp: String,
    val seconds: Double?,
    val record: Map<String, Any?>,
)

internal fun framebaseCandidates(
    rows: List<Any?>,
    maxDistance: Double,
): List<FramebaseCandidate> = rows.mapNotNull { value ->
    val row = (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: return@mapNotNull null
    val filename = gatewayHitFilename(row)
    val score = gatewayNumber(row["score"])
    if (filename.isBlank() || score == null || !score.isFinite() || score > maxDistance) return@mapNotNull null
    val timestamp = gatewayTimestamp13(row)
    val stream = gatewayFirstText(row, listOf("stream_name", "stream")).ifBlank { ARCHIVE_STREAM }
    val record = linkedMapOf<String, Any?>(
        "mode" to FRAMEBASE_MODE,
        "group_name" to ARCHIVE_COLLECTION,
        "modality" to FRAMEBASE_IMAGE_MODALITY,
        "stream_name" to stream,
        "filename" to filename,
    )
    if (timestamp.isNotBlank()) record["ts_unix_13digits"] = timestamp
    FramebaseCandidate(row, filename, timestamp, gatewayHitSeconds(row), record)
}

internal fun resolveImageUrls(
    records: List<Map<String, Any?>>,
    candidateCount: Int,
): Map<Int, String> {
    val result = linkedMapOf<Int, String>()
    records.forEachIndexed { position, row ->
        val index = row["input_index"]?.let(::gatewayInputIndex) ?: position
        if (index !in 0 until candidateCount || result.containsKey(index)) return@forEachIndexed
        if (row["found"] == false) return@forEachIndexed
        val url = row["url_pre_signed"]?.toString()?.trim().orEmpty()
        if (gatewayValidImageLocator(url)) result[index] = url
    }
    return result
}

@OptIn(ExperimentalEncodingApi::class)
internal fun resolveImageBytes(
    records: List<Map<String, Any?>>,
    urls: Map<Int, String>,
    locatorIndexes: List<Int> = urls.keys.sorted(),
): Map<Int, ByteArray> {
    val byUrl = linkedMapOf<String, ByteArray>()
    val byCandidate = linkedMapOf<Int, ByteArray>()
    records.forEachIndexed { position, row ->
        if (row["found"] == false) return@forEachIndexed
        val rawUrl = row["url_pre_signed"]?.toString()?.trim().orEmpty()
        val suppliedPosition = row["input_index"]?.let(::gatewayInputIndex)
        if (row.containsKey("input_index") && suppliedPosition == null) return@forEachIndexed
        if (suppliedPosition != null && suppliedPosition !in locatorIndexes.indices) return@forEachIndexed
        val urlCandidate = rawUrl.takeIf { it.isNotBlank() }?.let { value ->
            urls.entries.firstOrNull { it.value == value }?.key
        }
        val fallbackPosition = suppliedPosition ?: position
        if (urlCandidate == null && fallbackPosition !in locatorIndexes.indices) return@forEachIndexed
        val candidateIndex = urlCandidate ?: locatorIndexes[fallbackPosition]
        val expectedUrl = urls[candidateIndex] ?: return@forEachIndexed
        val url = rawUrl.ifBlank { expectedUrl }
        if (url != expectedUrl || byUrl.containsKey(url) || byCandidate.containsKey(candidateIndex)) return@forEachIndexed
        val encoded = listOf("content_base64", "img_base64", "image_base64")
            .asSequence()
            .map { row[it]?.toString()?.trim().orEmpty() }
            .firstOrNull { it.isNotBlank() }
            ?: return@forEachIndexed
        val bytes = try {
            Base64.decode(encoded)
        } catch (_: IllegalArgumentException) {
            return@forEachIndexed
        }
        if (bytes.isNotEmpty()) {
            byUrl[url] = bytes
            byCandidate[candidateIndex] = bytes
        }
    }
    return byCandidate
}

private fun framebaseMatches(
    candidates: List<FramebaseCandidate>,
    urls: Map<Int, String>,
    bytes: Map<Int, ByteArray>,
): List<FrameMatch> = candidates.mapIndexed { index, candidate ->
    val locator = urls[index]
    FrameMatch(
        row = candidate.row,
        filename = candidate.filename,
        timestamp = candidate.timestamp,
        seconds = candidate.seconds,
        imageBytes = bytes[index],
        imageUrl = locator?.takeIf(::gatewayIsHttps),
    )
}

internal fun gatewayHitFilename(row: Map<String, Any?>): String {
    val aliases = listOf("filename", "filename_sanitized", "video_filename", "video", "source_path", "path", "title")
    aliases.forEach { field ->
        val name = gatewayBasename(row[field]?.toString()?.trim().orEmpty())
        if (name.isNotBlank() && name != "." && name != "..") return name
    }
    var id = gatewayFirstText(row, listOf("item_id"))
    val stream = gatewayFirstText(row, listOf("stream", "stream_name"))
    val stamp = gatewayFirstText(row, listOf("ts_unix_13digits", "ts_unix", "timestamp_ms"))
    if (stream.isNotBlank() && id.startsWith("$stream-")) id = id.removePrefix("$stream-")
    if (stamp.isNotBlank() && id.endsWith("-$stamp")) id = id.removeSuffix("-$stamp")
    return gatewayBasename(id)
}

internal fun gatewayTimestamp13(row: Map<String, Any?>): String {
    val value = listOf("ts_unix_13digits", "ts_unix", "timestamp_ms")
        .asSequence()
        .mapNotNull { gatewayNumber(row[it]) }
        .firstOrNull { it.isFinite() && it >= 0.0 }
        ?: return ""
    val digits = value.toLongOrNullString() ?: return ""
    return when {
        digits.length >= 13 -> digits.take(13)
        digits.length == 10 -> (value.toLong() * 1000L).toString()
        else -> digits.padStart(13, '0')
    }
}

internal fun gatewayHitSeconds(row: Map<String, Any?>): Double? {
    listOf("video_time_seconds", "timestamp_seconds", "time_seconds", "start_seconds", "offset_seconds", "seconds", "time_sec")
        .asSequence()
        .mapNotNull { gatewayNumber(row[it]) }
        .firstOrNull { it.isFinite() && it >= 0.0 }
        ?.let { return it }
    val timestamp = listOf("ts_unix_13digits", "ts_unix", "timestamp_ms")
        .asSequence()
        .mapNotNull { gatewayNumber(row[it]) }
        .firstOrNull { it.isFinite() && it >= 0.0 }
    return timestamp?.takeIf { it < 86_400_000.0 }?.div(1000.0)
}

internal fun gatewayValidImageLocator(value: String): Boolean {
    val uri = try {
        URI(value)
    } catch (_: Exception) {
        return false
    }
    if (uri.scheme.equals("https", ignoreCase = true)) return !uri.host.isNullOrBlank()
    return uri.scheme == null && uri.rawAuthority == null && uri.path == FRAMEBASE_IMAGE_PATH
}

internal fun gatewayInputIndex(value: Any?): Int? {
    val number = gatewayNumber(value) ?: return null
    if (!number.isFinite() || number % 1.0 != 0.0 || number < Int.MIN_VALUE || number > Int.MAX_VALUE) return null
    return number.toInt()
}

private fun gatewayIsHttps(value: String): Boolean = try {
    val uri = URI(value)
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
} catch (_: Exception) {
    false
}

private fun gatewayFirstText(row: Map<String, Any?>, fields: List<String>): String = fields
    .asSequence()
    .map { row[it]?.toString()?.trim().orEmpty() }
    .firstOrNull { it.isNotBlank() && it != "null" }
    .orEmpty()

private fun gatewayBasename(value: String): String = value.replace('\\', '/').substringAfterLast('/').trim()

private fun gatewayNumber(value: Any?): Double? = when (value) {
    null -> null
    is Number -> value.toDouble()
    else -> value.toString().trim().toDoubleOrNull()
}

private fun Double.toLongOrNullString(): String? {
    if (!isFinite() || this < 0.0 || this > Long.MAX_VALUE.toDouble()) return null
    return toLong().toString()
}

private fun elapsedMs(start: Long): Long = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(0L)
