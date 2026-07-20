package com.vmodal.sdk.examples.fullapp

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vmodal.sdk.ApiError
import com.vmodal.sdk.Client
import com.vmodal.sdk.MutableApiKeyProvider
import com.vmodal.sdk.PUBLIC_GATEWAY_URL
import com.vmodal.sdk.SdkError
import com.vmodal.sdk.UploadSource
import com.vmodal.sdk.videoUploadAsync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val DEFAULT_COLLECTION = "android_example"
private const val DEFAULT_STREAM = "astream"

enum class FullAppAction {
    IDENTITY,
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
)

data class FullAppUiState(
    val action: FullAppAction? = null,
    val configured: Boolean = false,
    val userType: String = "",
    val collectionsLoaded: Boolean = false,
    val collections: List<String> = emptyList(),
    val collection: String = DEFAULT_COLLECTION,
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

    fun configure(apiKey: String) {
        val clean = apiKey.trim()
        if (clean.isBlank()) {
            mutableState.update { it.copy(error = "A runtime API key is required.") }
            return
        }
        cancelWork()
        searchGeneration++
        repo.configure(clean)
        mutableState.update {
            FullAppUiState(
                configured = true,
                collection = it.collection,
                stream = it.stream,
                query = it.query,
                selectedFile = source.fileName,
                status = "Client configured. Resolve identity to load its collections.",
            )
        }
    }

    fun resolveIdentity() {
        searchGeneration++
        mutableState.update(::resetSearch)
        runAction(FullAppAction.IDENTITY) {
            val output = withContext(Dispatchers.IO) { repo.resolveIdentity() }
            val current = mutableState.value
            val selected = if (output.collections.isNotEmpty() && current.collection !in output.collections) {
                output.collections.first()
            } else {
                current.collection
            }
            if (selected != current.collection) searchGeneration++
            mutableState.update { state ->
                val base = if (selected != state.collection) {
                    resetScope(state)
                } else {
                    state
                }
                base.copy(
                    action = null,
                    userType = output.userType,
                    collectionsLoaded = true,
                    collections = output.collections,
                    collection = selected,
                    status = if (output.collections.isEmpty()) {
                        "Authenticated user type: ${output.userType}. No existing video collections were found; upload to create one."
                    } else {
                        "Authenticated user type: ${output.userType}. Loaded ${output.collections.size} video collection(s)."
                    },
                )
            }
        }
    }

    fun refreshCollections() = runAction(FullAppAction.COLLECTIONS) {
        val names = withContext(Dispatchers.IO) { repo.listCollections() }
        mutableState.update {
            it.copy(
                action = null,
                collectionsLoaded = true,
                collections = names,
                status = if (names.isEmpty()) {
                    "No existing video collections are available for this API key."
                } else {
                    "Loaded ${names.size} video collection(s)."
                },
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
            val output = withContext(Dispatchers.IO) {
                repo.createIndex(state.collection.trim(), state.stream.trim())
            }
            mutableState.update {
                it.copy(
                    action = null,
                    indexJobId = output.jobId,
                    indexStatus = output.status.ifBlank { "queued" },
                    status = "Index job created. Refresh until its status is success.",
                )
            }
        }
    }

    fun refreshIndex() {
        val jobId = mutableState.value.indexJobId
        if (jobId.isBlank()) return showError("Create an index job first.")
        runAction(FullAppAction.INDEX_STATUS) {
            val output = withContext(Dispatchers.IO) { repo.indexStatus(jobId) }
            val status = output.status.ifBlank { "unknown" }
            mutableState.update {
                it.copy(
                    action = null,
                    indexStatus = status,
                    status = if (strIndexDone(status)) {
                        "Index is ready. Search the collection next."
                    } else {
                        "Index status refreshed: $status."
                    },
                )
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
            val output = withContext(Dispatchers.IO) {
                repo.search(scope.query, scope.collection, scope.stream)
            }
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
        if (!mutableState.value.configured) return showError("Configure the client first.")
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
                        it.copy(action = null, error = strUserError(error, it.collection))
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

        private fun strIndexDone(status: String): Boolean = status.trim().lowercase() in setOf(
            "success",
            "succeeded",
            "done",
            "completed",
            "ok",
        )

        private fun strUserError(error: Exception, collection: String): String {
            if (error is ApiError && error.statusCode == 404) {
                val body = error.body.toString().lowercase()
                return if ("missing lancedb" in body || "missing index" in body) {
                    "No searchable index exists for $collection. Upload the video and create its index before searching."
                } else {
                    "The configured gateway does not expose the requested resource."
                }
            }
            return if (error is SdkError) error.toString() else error.message ?: "Operation failed."
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

    fun resolveIdentity(): IdentityOutput {
        val client = requireClient()
        val me = client.auth.me()
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

    fun listCollections(): List<String> {
        lastCollections = requireClient().collections.listGroups("vid_file").data
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
    ): String = suspendCancellableCoroutine { continuation ->
        val handle = requireClient().collections.videoUploadAsync(
            source = source,
            collectionName = collection,
            subCollectionName = stream,
            onProgress = {
                if (continuation.isActive) onProgress(it.percent)
            },
            onSuccess = { result ->
                if (continuation.isActive) {
                    if (result.uploaded) {
                        continuation.resume(result.fileName.ifBlank { source.fileName })
                    } else {
                        continuation.resumeWithException(
                            IllegalStateException("Upload did not complete: ${result.raw}"),
                        )
                    }
                }
            },
            onFailure = { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            },
        )
        continuation.invokeOnCancellation { handle.cancel() }
    }

    fun createIndex(collection: String, stream: String): IndexOutput {
        val result = requireClient().indexes.createIndex(
            mode = "vid_file",
            groupName = collection,
            streamName = stream,
            indexType = "vid_img_emb",
            modality = "vid_img_emb",
            version = "new_version",
            reProcess = true,
        )
        return IndexOutput(result.jobId, result.status)
    }

    fun indexStatus(jobId: String): IndexOutput {
        val result = requireClient().indexes.indexStatus(jobId)
        return IndexOutput(result.jobId.ifBlank { jobId }, result.status)
    }

    fun search(query: String, collection: String, stream: String): SearchOutput {
        val client = requireClient()
        val groups = client.collections.listGroups("vid_file")
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
        val response = client.searches.searchVideo(
            queryText = query,
            mode = "vid_file",
            groupName = collection,
            streamName = stream,
            searchSources = listOf("image"),
            limit = 50,
            textEmbScoreMin = 0.0,
            imageEmbScoreMin = 0.0,
            versionLancedb = version,
        )
        val candidates = searchCandidates(response.data, collection, stream)
        val records = if (candidates.isEmpty()) {
            emptyList()
        } else {
            client.images.getUrlBulk(candidates.map { it.record }).records
        }
        return searchOutput(
            candidates = candidates,
            records = records,
            total = response.cntTotal,
            returned = response.cntActual,
            elapsedMs = response.executionTimeMs,
        )
    }

    fun clearCredentials() {
        keys?.clear()
        keys = null
        sdk = null
        lastCollections = emptyList()
    }

    private fun requireClient(): Client = requireNotNull(sdk) { "Configure the client first." }

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
    val liveTitle = strSearchFirst(row, "title").takeIf {
        filenameAlias.isBlank() &&
            strSearchFirst(row, "item_id").isNotBlank() &&
            strSearchFirst(row, "ts_unix_13digits", "ts_unix", "timestamp_ms").isNotBlank()
    }.orEmpty()
    val rawName = filenameAlias.ifBlank { liveTitle }
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
            "ocr",
            "asr",
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
