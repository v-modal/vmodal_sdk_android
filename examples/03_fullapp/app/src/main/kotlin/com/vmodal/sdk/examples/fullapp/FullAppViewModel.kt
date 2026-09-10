package com.vmodal.sdk.examples.fullapp

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vmodal.sdk.ApiError
import com.vmodal.sdk.AuthError
import com.vmodal.sdk.Client
import com.vmodal.sdk.FeatureDisabled
import com.vmodal.sdk.MutableApiKeyProvider
import com.vmodal.sdk.PUBLIC_GATEWAY_URL
import com.vmodal.sdk.SdkError
import com.vmodal.sdk.TransportError
import com.vmodal.sdk.UploadSource
import com.vmodal.sdk.ValidationFailed
import com.vmodal.sdk.VideoUploadEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val DEFAULT_STREAM = "astream"
private const val COLLECTION_PREVIEW_LIMIT = 5
private const val INDEX_POLL_MS = 5_000L
private const val INDEX_TIMEOUT_MS = 600_000L

enum class FullAppAction {
    CONNECT,
    COLLECTIONS,
    UPLOAD,
    CREATE_INDEX,
    INDEX_STATUS,
    SEARCH,
}

data class SearchImage(
    val id: String,
    val url: String,
    val title: String,
    val filename: String,
    val stream: String,
    val timestamp: String,
    val score: String,
    val bytes: ByteArray = byteArrayOf(),
)

data class FullAppUiState(
    val action: FullAppAction? = null,
    val configured: Boolean = false,
    val userType: String = "",
    val collectionsLoaded: Boolean = false,
    val collections: List<String> = emptyList(),
    val collection: String = strNewDemoCollection(),
    val stream: String = DEFAULT_STREAM,
    val query: String = "red",
    val selectedFile: String = "video_10frames.mp4",
    val uploadProgress: Int = 0,
    val uploadedFile: String = "",
    val indexJobId: String = "",
    val indexStatus: String = "not started",
    val searched: Boolean = false,
    val images: List<SearchImage> = emptyList(),
    val searchTotal: Int = 0,
    val searchReturned: Int = 0,
    val searchElapsedMs: Double = 0.0,
    val status: String = "Enter a runtime API key supplied by your authenticated app.",
    val error: String = "",
)

internal data class IdentityOutput(
    val userType: String,
    val collections: List<String>,
)

internal data class IndexOutput(val jobId: String, val status: String)

internal data class SearchCandidate(
    val searchRank: Int,
    val row: Map<String, Any?>,
    val record: Map<String, Any?>,
)

internal data class SearchOutput(
    val images: List<SearchImage>,
    val total: Int,
    val returned: Int,
    val elapsedMs: Double,
)

private data class SearchScope(val query: String, val collection: String, val stream: String)

internal fun strNewDemoCollection(): String =
    "android_demo_${UUID.randomUUID().toString().replace("-", "").take(10)}"

internal fun strCollectionSummary(names: List<String>): String {
    if (names.isEmpty()) return "No existing video collections. Upload will create the generated collection."
    val preview = names.take(COLLECTION_PREVIEW_LIMIT).joinToString()
    val remaining = names.size - COLLECTION_PREVIEW_LIMIT
    val suffix = if (remaining > 0) " and $remaining more" else ""
    return "${names.size} existing video collection(s): $preview$suffix. " +
        "Keep the generated name for an isolated demo, or enter one shown here."
}

internal fun strIndexDone(status: String): Boolean = status.trim().lowercase() in setOf(
    "success",
    "succeeded",
    "done",
    "completed",
    "ok",
)

internal fun strIndexFailed(status: String): Boolean = status.trim().lowercase() in setOf(
    "failed",
    "failure",
    "error",
    "cancelled",
    "canceled",
    "dead_letter",
    "timeout",
    "timed_out",
    "expired",
)

internal fun strFullAppError(error: Exception, collection: String): String = when (error) {
    is AuthError -> "API key rejected${strHttpStatus(error.statusCode)}. Check or replace the key and connect again."
    is ValidationFailed -> "Invalid input: ${error.message ?: "check the entered values"}."
    is TransportError -> "Cannot reach VModal. Check the device internet connection and try again."
    is FeatureDisabled -> "This operation is not available for the configured service."
    is ApiError -> when {
        error.statusCode == 404 && strMissingIndex(error.body) ->
            "No searchable index exists for $collection. Upload a video, create its index, and wait until it is ready."
        error.statusCode == 404 ->
            "Collection or resource not found (HTTP 404). Use a collection visible to this API key, or upload to the generated collection."
        error.statusCode == 429 -> "VModal rate limit reached (HTTP 429). Wait briefly and try again."
        error.statusCode >= 500 -> "VModal is temporarily unavailable${strHttpStatus(error.statusCode)}. Try again later."
        else -> "VModal request failed${strHttpStatus(error.statusCode)}. Check the selected scope and try again."
    }
    is IllegalArgumentException, is IllegalStateException -> error.message ?: "Operation failed."
    is SdkError -> "VModal operation failed${strHttpStatus(error.statusCode)}. Try again."
    else -> "Unexpected ${error::class.simpleName ?: "error"}. Try again."
}

private fun strHttpStatus(status: Int): String = if (status > 0) " (HTTP $status)" else ""

private fun strMissingIndex(body: Any?): Boolean {
    val text = body.toString().lowercase()
    return "missing lancedb" in text || "missing index" in text
}

class FullAppViewModel private constructor(
    private val repo: FullAppRepository,
    private val sample: UploadSource,
) : ViewModel() {
    private val mutableState = MutableStateFlow(FullAppUiState())
    val state: StateFlow<FullAppUiState> = mutableState.asStateFlow()
    private var source: UploadSource = sample
    private var workJob: Job? = null
    private var workGeneration = 0L
    private var searchGeneration = 0L

    fun connect(apiKey: String) {
        val clean = apiKey.trim()
        if (clean.isBlank()) {
            mutableState.update { it.copy(error = "A runtime API key is required.") }
            return
        }
        cancelWork()
        searchGeneration++
        val generation = ++workGeneration
        mutableState.update {
            FullAppUiState(
                action = FullAppAction.CONNECT,
                collection = it.collection,
                stream = it.stream,
                query = it.query,
                selectedFile = source.fileName,
                status = "Checking the API key and loading its collections…",
            )
        }
        workJob = viewModelScope.launch {
            try {
                repo.configure(clean)
                val output = repo.resolveIdentity()
                if (generation != workGeneration) return@launch
                mutableState.update {
                    it.copy(
                        action = null,
                        configured = true,
                        userType = output.userType,
                        collectionsLoaded = true,
                        collections = output.collections,
                        status = "Connected as ${output.userType}. ${strCollectionSummary(output.collections)}",
                    )
                }
            } catch (error: CancellationException) {
                if (generation == workGeneration) {
                    repo.clearCredentials()
                    mutableState.update { it.copy(action = null, configured = false) }
                }
                throw error
            } catch (error: Exception) {
                if (generation == workGeneration) {
                    repo.clearCredentials()
                    mutableState.update {
                        it.copy(
                            action = null,
                            configured = false,
                            userType = "",
                            collectionsLoaded = false,
                            collections = emptyList(),
                            status = "Connection failed. Correct the problem below and try again.",
                            error = strFullAppError(error, it.collection),
                        )
                    }
                }
            }
        }
    }

    fun refreshCollections() = runAction(FullAppAction.COLLECTIONS) {
        val names = repo.listCollections()
        mutableState.update {
            it.copy(
                action = null,
                collectionsLoaded = true,
                collections = names,
                status = strCollectionSummary(names),
            )
        }
    }

    fun setCollection(value: String) {
        cancelWork()
        searchGeneration++
        mutableState.update { resetScope(it).copy(action = null, collection = value) }
    }

    fun setStream(value: String) {
        cancelWork()
        searchGeneration++
        mutableState.update { resetScope(it).copy(action = null, stream = value) }
    }

    fun setQuery(value: String) {
        if (value == mutableState.value.query) return
        val searching = mutableState.value.action == FullAppAction.SEARCH
        if (searching) cancelWork()
        searchGeneration++
        mutableState.update {
            resetSearch(it).copy(
                action = if (searching) null else it.action,
                query = value,
            )
        }
    }

    fun selectVideo(value: UploadSource) {
        cancelWork()
        searchGeneration++
        source = value
        mutableState.update {
            resetUpload(it).copy(
                action = null,
                selectedFile = value.fileName,
                status = "Selected ${value.fileName}. Upload it next.",
                error = "",
            )
        }
    }

    fun selectionError(message: String) {
        mutableState.update { it.copy(error = message) }
    }

    fun useBundledSample() {
        selectVideo(sample)
        mutableState.update { it.copy(status = "Bundled 10-frame sample video is ready.") }
    }

    fun upload() {
        val state = mutableState.value
        val error = strValidation(state, requireQuery = false)
        if (error.isNotBlank()) return showError(error)
        searchGeneration++
        mutableState.update(::resetSearch)
        runAction(FullAppAction.UPLOAD) {
            val fileName = repo.upload(source, state.collection.trim(), state.stream.trim()) { progress ->
                mutableState.update { it.copy(uploadProgress = progress) }
            }
            mutableState.update {
                it.copy(
                    action = null,
                    uploadProgress = 100,
                    uploadedFile = fileName,
                    indexJobId = "",
                    indexStatus = "not started",
                    searched = false,
                    images = emptyList(),
                    status = "Upload complete: $fileName. Create its index next.",
                )
            }
        }
    }

    fun cancelUpload() {
        cancelWork()
        mutableState.update { it.copy(action = null, status = "Upload canceled.") }
    }

    fun createIndex() {
        val state = mutableState.value
        val error = strValidation(state, requireQuery = false)
        if (error.isNotBlank()) return showError(error)
        searchGeneration++
        mutableState.update(::resetSearch)
        runAction(FullAppAction.CREATE_INDEX) {
            var output = repo.createIndex(state.collection.trim(), state.stream.trim())
            val jobId = output.jobId
            check(jobId.isNotBlank()) { "Index creation returned no job ID. Try creating the index again." }
            val deadline = System.currentTimeMillis() + INDEX_TIMEOUT_MS
            while (true) {
                val status = output.status.ifBlank { "queued" }
                mutableState.update {
                    it.copy(
                        action = if (strIndexDone(status)) null else FullAppAction.INDEX_STATUS,
                        indexJobId = jobId,
                        indexStatus = status,
                        status = if (strIndexDone(status)) {
                            "Index is ready. Search the collection next."
                        } else {
                            "Indexing is $status. Waiting automatically…"
                        },
                    )
                }
                if (strIndexDone(status)) break
                check(!strIndexFailed(status)) {
                    "Indexing stopped with status '$status'. Check the collection and stream, then create the index again."
                }
                check(System.currentTimeMillis() < deadline) {
                    "Indexing did not finish within 10 minutes. Create the index again or retry later."
                }
                delay(INDEX_POLL_MS)
                output = repo.indexStatus(jobId)
            }
        }
    }

    fun search() {
        val state = mutableState.value
        val error = strValidation(state, requireQuery = true)
        if (error.isNotBlank()) return showError(error)
        val scope = SearchScope(state.query.trim(), state.collection.trim(), state.stream.trim())
        val generation = ++searchGeneration
        mutableState.update { resetSearch(it).copy(action = FullAppAction.SEARCH, error = "") }
        runAction(FullAppAction.SEARCH) {
            val output = repo.search(scope.query, scope.collection, scope.stream)
            if (!isCurrentSearch(generation, scope)) return@runAction
            val names = repo.lastCollections
            mutableState.update {
                it.copy(
                    action = null,
                    collectionsLoaded = true,
                    collections = names,
                    searched = true,
                    images = output.images,
                    searchTotal = output.total,
                    searchReturned = output.returned,
                    searchElapsedMs = output.elapsedMs,
                    status = "Search resolved ${output.images.size} images from ${output.total} matches in ${scope.collection}/${scope.stream}.",
                )
            }
        }
    }

    fun forgetApiKey() {
        cancelWork()
        searchGeneration++
        repo.clearCredentials()
        source = sample
        mutableState.value = FullAppUiState()
    }

    private fun runAction(action: FullAppAction, task: suspend () -> Unit) {
        if (!mutableState.value.configured) return showError("Connect with a valid API key first.")
        workJob?.cancel()
        val generation = ++workGeneration
        workJob = viewModelScope.launch {
            mutableState.update { it.copy(action = action, error = "") }
            try {
                task()
            } catch (error: CancellationException) {
                if (generation == workGeneration) {
                    mutableState.update { it.copy(action = null) }
                }
                throw error
            } catch (error: Exception) {
                if (generation == workGeneration) {
                    mutableState.update {
                        it.copy(
                            action = null,
                            status = "Operation stopped. Correct the problem below and try again.",
                            error = strFullAppError(error, it.collection),
                        )
                    }
                }
            }
        }
    }

    private fun showError(message: String) {
        mutableState.update { it.copy(error = message) }
    }

    private fun resetScope(state: FullAppUiState): FullAppUiState = resetUpload(state).copy(
        status = "Collection or stream changed. Upload or index this scope next.",
    )

    private fun resetUpload(state: FullAppUiState): FullAppUiState = state.copy(
        uploadProgress = 0,
        uploadedFile = "",
        indexJobId = "",
        indexStatus = "not started",
        searched = false,
        images = emptyList(),
        searchTotal = 0,
        searchReturned = 0,
        searchElapsedMs = 0.0,
    )

    private fun resetSearch(state: FullAppUiState): FullAppUiState = state.copy(
        searched = false,
        images = emptyList(),
        searchTotal = 0,
        searchReturned = 0,
        searchElapsedMs = 0.0,
    )

    private fun cancelWork() {
        workGeneration++
        workJob?.cancel()
        workJob = null
    }

    private fun isCurrentSearch(generation: Long, scope: SearchScope): Boolean {
        val state = mutableState.value
        return generation == searchGeneration &&
            state.configured &&
            state.query.trim() == scope.query &&
            state.collection.trim() == scope.collection &&
            state.stream.trim() == scope.stream
    }

    override fun onCleared() {
        cancelWork()
        repo.clearCredentials()
        super.onCleared()
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val sample = assetUploadSource(context.applicationContext)
                    return FullAppViewModel(FullAppRepository(), sample) as T
                }
            }

        private fun strValidation(state: FullAppUiState, requireQuery: Boolean): String = when {
            state.collection.trim().isBlank() -> "Collection is required."
            state.stream.trim().isBlank() -> "Stream is required."
            requireQuery && state.query.trim().isBlank() -> "Search text is required."
            else -> ""
        }

    }
}

internal class FullAppRepository {
    private var keys: MutableApiKeyProvider? = null
    private var sdk: Client? = null
    var lastCollections: List<String> = emptyList()
        private set

    fun configure(apiKey: String) {
        clearCredentials()
        keys = MutableApiKeyProvider(apiKey)
        sdk = Client(baseUrl = PUBLIC_GATEWAY_URL, apiKeyProvider = keys)
    }

    suspend fun resolveIdentity(): IdentityOutput {
        val client = requireClient()
        val me = client.coroutines().auth.me()
        val userId = requireNotNull(me.userId) { "auth/me returned no user_id" }
        sdk = Client(
            client.cfg.copy(
                userId = userId,
                tenantId = me.tenantId.orEmpty(),
                email = me.email.orEmpty(),
            )
        )
        return IdentityOutput(me.type, listCollections())
    }

    suspend fun listCollections(): List<String> {
        lastCollections = requireClient().coroutines().collections.listGroups("vid_file").data
            .asSequence()
            .filter { it.mode == "vid_file" }
            .map { it.groupName.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .toList()
        return lastCollections
    }

    suspend fun upload(
        source: UploadSource,
        collection: String,
        stream: String,
        onProgress: (Int) -> Unit,
    ): String {
        var completed = ""
        requireClient().coroutines().collections.videoUploadEvents(
            source = source,
            collectionName = collection,
            subCollectionName = stream,
        ).collect { event ->
            when (event) {
                is VideoUploadEvent.Progress -> onProgress(event.progress.percent)
                is VideoUploadEvent.Completed -> {
                    check(event.response.uploaded) {
                        "Upload did not complete. Check the selected video and try again."
                    }
                    completed = event.response.fileName.ifBlank { source.fileName }
                }
            }
        }
        return completed.ifBlank { error("Upload completed without a result.") }
    }

    suspend fun createIndex(collection: String, stream: String): IndexOutput {
        val result = requireClient().coroutines().indexes.createIndex(
            mode = "vid_file",
            groupName = collection,
            streamName = stream,
            version = "new_version",
            reProcess = true,
        )
        return IndexOutput(result.jobId, result.status)
    }

    suspend fun indexStatus(jobId: String): IndexOutput {
        val result = requireClient().coroutines().indexes.indexStatus(jobId)
        return IndexOutput(result.jobId.ifBlank { jobId }, result.status)
    }

    suspend fun search(query: String, collection: String, stream: String): SearchOutput {
        val client = requireClient()
        val coroutines = client.coroutines()
        val groups = coroutines.collections.listGroups("vid_file")
        lastCollections = groups.data
            .filter { it.mode == "vid_file" }
            .map { it.groupName.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
        val item = groups.findGroup(collection, "vid_file")
            ?: error("Collection $collection is not available for this API key. Choose a loaded collection or upload it first.")
        val version = item.latestLancedbVersion
            ?: error("Collection $collection has no advertised LanceDB index version. Create or finish its image index before searching.")
        val response = coroutines.searches.searchVideo(
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
        val records = if (candidates.isEmpty()) {
            emptyList()
        } else {
            coroutines.images.getUrlBulk(candidates.map { it.record }).records
        }
        val output = searchOutput(
            candidates = candidates,
            records = records,
            total = response.cntTotal,
            returned = response.cntActual,
            elapsedMs = response.executionTimeMs,
        )
        if (output.images.isEmpty()) return output
        val content = coroutines.images.getImageBulkFromUrls(output.images.map(SearchImage::url)).records
        return output.copy(images = searchImageBytes(output.images, content))
    }

    fun clearCredentials() {
        keys?.clear()
        keys = null
        sdk = null
        lastCollections = emptyList()
    }

    private fun requireClient(): Client = requireNotNull(sdk) { "Connect with a valid API key first." }

}

internal fun searchCandidates(
    values: List<Any?>,
    collection: String,
    stream: String,
): List<SearchCandidate> = values.mapIndexedNotNull { rank, value ->
    val row = strSearchMap(value) ?: return@mapIndexedNotNull null
    val filenameAlias = strSearchFirst(
        row,
        "filename",
        "filename_sanitized",
        "video_filename",
        "video",
        "source_path",
        "path",
    )
    val rawName = filenameAlias.ifBlank { candidateFileName(row) }
    val filename = strSearchFilename(rawName)
    if (filename.isBlank()) return@mapIndexedNotNull null

    val record = linkedMapOf<String, Any?>(
        "mode" to "vid_file",
        "group_name" to collection.trim(),
        "modality" to "vid_img",
        "stream_name" to strSearchFirst(row, "stream", "stream_name").ifBlank { stream.trim() },
        "filename" to filename,
    )
    val stamp = strTimestamp13(strSearchFirst(row, "ts_unix_13digits", "ts_unix", "timestamp_ms"))
    if (stamp.isNotBlank()) record["ts_unix_13digits"] = stamp
    SearchCandidate(rank, row, record)
}
private fun candidateFileName(row: Map<String, Any?>): String {
    // Prefer the explicit title, then reconstruct from item_id.
    val title = strSearchFirst(row, "title")
    if (title.isNotBlank()) return title

    val id = strSearchFirst(row, "item_id")
    val stream = strSearchFirst(row, "stream")
    val unix = strSearchFirst(row, "ts_unix")
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
    candidates: List<SearchCandidate>,
    records: List<Map<String, Any?>>,
): List<SearchImage> {
    val resolved = mutableMapOf<Int, SearchImage>()
    records.forEachIndexed { rowIndex, row ->
        val rawIndex = row["input_index"]
        val inputIndex = if (rawIndex == null) rowIndex else intInputIndex(rawIndex)
        if (inputIndex == null || inputIndex !in candidates.indices || inputIndex in resolved) {
            return@forEachIndexed
        }
        if (row["found"] == false) return@forEachIndexed
        val url = row["url_pre_signed"]?.toString()?.trim().orEmpty()
        if (url.isBlank()) return@forEachIndexed

        val candidate = candidates[inputIndex]
        val filename = candidate.record["filename"]?.toString()?.trim().orEmpty()
        val timestamp = candidate.record["ts_unix_13digits"]?.toString()?.trim().orEmpty()
        val stream = candidate.record["stream_name"]?.toString()?.trim().orEmpty()
        val title = strSearchFirst(
            candidate.row,
            "effective_title",
            "title",
            "text",
            "caption",
            "description",
            "item_id",
            "text_agg_tok",
        ).ifBlank { filename }
        resolved[inputIndex] = SearchImage(
            id = "${candidate.searchRank}-$filename-$timestamp",
            url = url,
            title = title,
            filename = filename,
            stream = stream,
            timestamp = timestamp,
            score = strSearchScore(candidate.row),
        )
    }
    return resolved.toSortedMap().values.toList()
}

internal fun searchOutput(
    candidates: List<SearchCandidate>,
    records: List<Map<String, Any?>>,
    total: Int,
    returned: Int,
    elapsedMs: Double,
): SearchOutput = SearchOutput(searchImages(candidates, records), total, returned, elapsedMs)

@OptIn(ExperimentalEncodingApi::class)
internal fun searchImageBytes(
    images: List<SearchImage>,
    records: List<Map<String, Any?>>,
): List<SearchImage> {
    val content = mutableMapOf<Int, ByteArray>()
    records.forEachIndexed { rowIndex, row ->
        val rawIndex = row["input_index"]
        val inputIndex = if (rawIndex == null) rowIndex else intInputIndex(rawIndex)
        if (inputIndex == null || inputIndex !in images.indices || inputIndex in content) return@forEachIndexed
        if (row["found"] == false) return@forEachIndexed
        val encoded = row["content_base64"]?.toString()?.trim().orEmpty()
        if (encoded.isBlank()) return@forEachIndexed
        val bytes = runCatching { Base64.Default.decode(encoded) }.getOrNull() ?: byteArrayOf()
        if (bytes.isNotEmpty()) content[inputIndex] = bytes
    }
    return images.mapIndexed { index, image -> image.copy(bytes = content[index] ?: byteArrayOf()) }
}

internal fun strSearchFilename(value: String): String =
    value.trim().replace('\\', '/').substringAfterLast('/').trim()

internal fun strTimestamp13(value: String): String {
    val digits = value.filter(Char::isDigit)
    return when {
        digits.length >= 13 -> digits.take(13)
        digits.length == 10 -> "${digits}000"
        digits.isNotBlank() -> digits.padStart(13, '0')
        else -> ""
    }
}

internal fun strSearchScore(row: Map<String, Any?>): String {
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

private fun strSearchMap(value: Any?): Map<String, Any?>? =
    (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }

private fun strSearchFirst(row: Map<String, Any?>, vararg names: String): String =
    names.firstNotNullOfOrNull { name -> row[name]?.toString()?.trim()?.takeIf { it.isNotEmpty() } }
        .orEmpty()

private fun intInputIndex(value: Any?): Int? = when (value) {
    is Number -> value.toDouble().takeIf { it.isFinite() }?.toInt()
    is String -> value.trim().toIntOrNull()
    else -> null
}
