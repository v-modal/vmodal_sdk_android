package com.vmodal.sdk.examples.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vmodal.sdk.Client
import com.vmodal.sdk.MutableApiKeyProvider
import com.vmodal.sdk.PUBLIC_GATEWAY_URL
import com.vmodal.sdk.UploadSource
import com.vmodal.sdk.videoUploadAsync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class WorkflowAction {
    UPLOAD,
    INDEX,
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

data class SearchUiState(
    val action: WorkflowAction? = null,
    val selectedFile: String = "",
    val uploadProgress: Int = 0,
    val uploadedFile: String = "",
    val indexJobId: String = "",
    val indexStatus: String = "",
    val indexReady: Boolean = false,
    val searched: Boolean = false,
    val images: List<SearchImage> = emptyList(),
    val total: Int = 0,
    val elapsedMs: Double = 0.0,
    val error: String = "",
)

private data class SearchOutput(
    val images: List<SearchImage>,
    val total: Int,
    val elapsedMs: Double,
)

private data class IndexOutput(val jobId: String, val status: String)

class SearchViewModel private constructor(private val repo: SearchRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = mutableState.asStateFlow()
    private var workJob: Job? = null
    private var source: UploadSource? = null

    fun selectVideo(value: UploadSource) {
        source = value
        mutableState.update {
            it.copy(
                selectedFile = value.fileName,
                uploadProgress = 0,
                uploadedFile = "",
                indexJobId = "",
                indexStatus = "",
                indexReady = false,
                searched = false,
                images = emptyList(),
                total = 0,
                elapsedMs = 0.0,
                error = "",
            )
        }
    }

    fun selectionError(message: String) {
        source = null
        mutableState.update { it.copy(selectedFile = "", error = message) }
    }

    fun coordinatesChanged() {
        mutableState.update {
            it.copy(
                uploadProgress = 0,
                uploadedFile = "",
                indexJobId = "",
                indexStatus = "",
                indexReady = false,
                searched = false,
                images = emptyList(),
                total = 0,
                elapsedMs = 0.0,
                error = "",
            )
        }
    }

    fun upload(apiKey: String, group: String, stream: String) {
        val cleanKey = apiKey.trim()
        val cleanGroup = group.trim()
        val cleanStream = stream.trim()
        val input = source
        val validation = strValidation(cleanKey, cleanGroup, cleanStream)
            .ifBlank { if (input == null) "Choose a video first." else "" }
        if (validation.isNotBlank()) {
            mutableState.update { it.copy(error = validation) }
            return
        }

        workJob?.cancel()
        workJob = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    action = WorkflowAction.UPLOAD,
                    uploadProgress = 0,
                    uploadedFile = "",
                    indexJobId = "",
                    indexStatus = "",
                    indexReady = false,
                    searched = false,
                    images = emptyList(),
                    total = 0,
                    elapsedMs = 0.0,
                    error = "",
                )
            }
            try {
                val fileName = withContext(Dispatchers.IO) {
                    repo.upload(cleanKey, requireNotNull(input), cleanGroup, cleanStream) { progress ->
                        mutableState.update { it.copy(uploadProgress = progress) }
                    }
                }
                mutableState.update {
                    it.copy(action = null, uploadProgress = 100, uploadedFile = fileName)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(action = null, error = e.message ?: "Upload failed.")
                }
            }
        }
    }

    fun createIndex(apiKey: String, group: String, stream: String) {
        val cleanKey = apiKey.trim()
        val cleanGroup = group.trim()
        val cleanStream = stream.trim()
        val validation = strValidation(cleanKey, cleanGroup, cleanStream)
        if (validation.isNotBlank()) {
            mutableState.update { it.copy(error = validation) }
            return
        }

        workJob?.cancel()
        workJob = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    action = WorkflowAction.INDEX,
                    indexJobId = "",
                    indexStatus = "submitting",
                    indexReady = false,
                    searched = false,
                    images = emptyList(),
                    total = 0,
                    elapsedMs = 0.0,
                    error = "",
                )
            }
            try {
                val created = withContext(Dispatchers.IO) {
                    repo.createIndex(cleanKey, cleanGroup, cleanStream)
                }
                require(created.jobId.isNotBlank()) { "Index creation returned no job ID." }
                mutableState.update {
                    it.copy(indexJobId = created.jobId, indexStatus = created.status.ifBlank { "queued" })
                }

                val end = System.currentTimeMillis() + INDEX_TIMEOUT_MS
                while (System.currentTimeMillis() <= end) {
                    val current = withContext(Dispatchers.IO) { repo.indexStatus(created.jobId) }
                    val status = current.status.trim().lowercase().ifBlank { "unknown" }
                    mutableState.update { it.copy(indexStatus = status) }
                    if (status in INDEX_OK) {
                        mutableState.update { it.copy(action = null, indexReady = true) }
                        return@launch
                    }
                    require(status !in INDEX_FAIL) { "Index job ${created.jobId} ended with $status." }
                    delay(INDEX_POLL_MS)
                }
                error("Indexing timed out after 30 minutes.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(action = null, error = e.message ?: "Index creation failed.")
                }
            }
        }
    }

    fun search(apiKey: String, query: String, group: String, stream: String) {
        val cleanKey = apiKey.trim()
        val cleanQuery = query.trim()
        val cleanGroup = group.trim()
        val cleanStream = stream.trim()
        val validation = when {
            strValidation(cleanKey, cleanGroup, cleanStream).isNotBlank() ->
                strValidation(cleanKey, cleanGroup, cleanStream)
            cleanQuery.isBlank() -> "Enter search text."
            else -> ""
        }
        if (validation.isNotBlank()) {
            mutableState.update { it.copy(searched = true, error = validation) }
            return
        }

        workJob?.cancel()
        workJob = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    action = WorkflowAction.SEARCH,
                    searched = false,
                    images = emptyList(),
                    total = 0,
                    elapsedMs = 0.0,
                    error = "",
                )
            }
            try {
                val output = withContext(Dispatchers.IO) {
                    repo.search(cleanKey, cleanQuery, cleanGroup, cleanStream)
                }
                mutableState.update {
                    it.copy(
                        action = null,
                        searched = true,
                        images = output.images,
                        total = output.total,
                        elapsedMs = output.elapsedMs,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(
                        action = null,
                        searched = true,
                        error = e.message ?: "Search failed.",
                    )
                }
            }
        }
    }

    fun clearCredentials() {
        workJob?.cancel()
        source = null
        repo.clearCredentials()
        mutableState.value = SearchUiState()
    }

    override fun onCleared() {
        repo.clearCredentials()
        super.onCleared()
    }

    companion object {
        private val INDEX_OK = setOf("success", "succeeded", "done", "completed", "ok")
        private val INDEX_FAIL = setOf("failed", "failure", "error", "cancelled", "canceled", "dead_letter")
        private const val INDEX_POLL_MS = 5_000L
        private const val INDEX_TIMEOUT_MS = 30 * 60 * 1_000L

        private fun strValidation(apiKey: String, group: String, stream: String): String = when {
            apiKey.isBlank() -> "Enter a runtime API key."
            group.isBlank() -> "Enter a collection name."
            stream.isBlank() -> "Enter a stream name."
            else -> ""
        }

        fun factory(): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SearchViewModel(SearchRepository()) as T
            }
    }
}

private class SearchRepository {
    private var keys: MutableApiKeyProvider? = null
    private var sdk: Client? = null

    suspend fun upload(
        apiKey: String,
        source: UploadSource,
        group: String,
        stream: String,
        onProgress: (Int) -> Unit,
    ): String = suspendCancellableCoroutine { continuation ->
        val handle = client(apiKey).collections.videoUploadAsync(
            source = source,
            collectionName = group,
            subCollectionName = stream,
            mode = "vid_file",
            modality = "vid_raw",
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

    fun createIndex(apiKey: String, group: String, stream: String): IndexOutput {
        val result = client(apiKey).indexes.createIndex(
            mode = "vid_file",
            groupName = group,
            streamName = stream,
            indexType = "vid_img_emb",
            modality = "vid_img_emb",
            version = "new_version",
            reProcess = true,
        )
        return IndexOutput(result.jobId, result.status)
    }

    fun indexStatus(jobId: String): IndexOutput {
        val result = requireNotNull(sdk) { "Authenticate before checking index status." }
            .indexes.indexStatus(jobId)
        return IndexOutput(result.jobId.ifBlank { jobId }, result.status)
    }

    fun search(apiKey: String, query: String, group: String, stream: String): SearchOutput {
        val client = client(apiKey)
        val item = client.collections.listGroups("vid_file").findGroup(group, "vid_file")
            ?: error("Collection $group is not available for this API key. Choose a listed video collection.")
        val version = item.latestLancedbVersion
            ?: error("Collection $group has no advertised LanceDB index version. Finish its image index before searching.")
        val result = client.searches.searchVideo(
            queryText = query,
            mode = "vid_file",
            groupName = group,
            streamName = stream,
            searchSources = listOf("ocr", "asr", "image"),
            limit = 50,
            versionLancedb = version,
        )

        val candidates = result.data.mapNotNull(::stringMap).mapNotNull { hit ->
            val record = imageRecord(hit, group, stream) ?: return@mapNotNull null
            hit to record
        }
        if (candidates.isEmpty()) {
            return SearchOutput(emptyList(), result.cntTotal, result.executionTimeMs)
        }

        val urls = client.images.getUrlBulk(candidates.map { it.second }).records
        val images = urls.mapIndexedNotNull { index, row ->
            val inputIndex = (row["input_index"] as? Number)?.toInt() ?: index
            val url = row["url_pre_signed"]?.toString().orEmpty()
            if (url.isBlank() || inputIndex !in candidates.indices) return@mapIndexedNotNull null

            val hit = candidates[inputIndex].first
            val filename = candidates[inputIndex].second["filename"]?.toString().orEmpty()
            val title = firstText(hit, "text", "caption", "ocr", "asr", "description")
                .ifBlank { filename }
            val stamp = candidates[inputIndex].second["ts_unix_13digits"]?.toString().orEmpty()
            SearchImage(
                id = "$filename-$stamp-$inputIndex",
                url = url,
                title = title,
                filename = filename,
                stream = firstText(hit, "stream_name").ifBlank { stream },
                timestamp = stamp,
                score = firstText(hit, "score", "similarity", "image_score", "text_score"),
            )
        }
        return SearchOutput(images, result.cntTotal, result.executionTimeMs)
    }

    @Synchronized
    private fun client(apiKey: String): Client {
        val provider = keys ?: MutableApiKeyProvider(apiKey).also { keys = it }
        provider.rotate(apiKey)
        sdk?.let { return it }

        val authClient = Client(
            baseUrl = PUBLIC_GATEWAY_URL,
            apiKeyProvider = provider,
        )
        val me = authClient.auth.me()
        val userId = requireNotNull(me.userId) { "auth/me returned no user_id" }
        return Client(
            authClient.cfg.copy(
                userId = userId,
                tenantId = me.tenantId.orEmpty(),
                email = me.email.orEmpty(),
            )
        ).also { sdk = it }
    }

    @Synchronized
    fun clearCredentials() {
        keys?.clear()
        keys = null
        sdk = null
    }

    private fun imageRecord(
        hit: Map<String, Any?>,
        group: String,
        stream: String,
    ): Map<String, Any?>? {
        val rawName = firstText(
            hit,
            "filename",
            "filename_sanitized",
            "video_filename",
            "video",
            "source_path",
            "path",
        )
        val filename = rawName.substringAfterLast('/').substringAfterLast('\\')
        if (filename.isBlank()) return null

        val record = linkedMapOf<String, Any?>(
            "mode" to "vid_file",
            "group_name" to group,
            "modality" to "image",
            "stream_name" to firstText(hit, "stream_name").ifBlank { stream },
            "filename" to filename,
        )
        val stamp = timestamp13(firstText(hit, "ts_unix_13digits", "ts_unix", "timestamp_ms"))
        if (stamp.isNotBlank()) record["ts_unix_13digits"] = stamp
        return record
    }

    private fun firstText(row: Map<String, Any?>, vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> row[key]?.toString()?.trim()?.takeIf { it.isNotBlank() } }
            .orEmpty()

    private fun stringMap(value: Any?): Map<String, Any?>? =
        (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }

    private fun timestamp13(value: String): String {
        val digits = value.filter(Char::isDigit)
        return when {
            digits.length >= 13 -> digits.take(13)
            digits.length == 10 -> (digits.toLong() * 1000).toString()
            digits.isNotBlank() -> digits.padStart(13, '0')
            else -> ""
        }
    }
}
