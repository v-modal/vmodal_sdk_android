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

data class SearchItem(
    val id: String,
    val title: String,
    val details: String,
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
    val results: List<SearchItem> = emptyList(),
    val status: String = "Enter a runtime API key supplied by your authenticated app.",
    val error: String = "",
)

private data class IdentityOutput(
    val userType: String,
    val collections: List<String>,
)

private data class IndexOutput(val jobId: String, val status: String)

private data class SearchOutput(
    val items: List<SearchItem>,
    val count: Int,
)

class FullAppViewModel private constructor(
    private val repo: FullAppRepository,
    private val sample: UploadSource,
) : ViewModel() {
    private val mutableState = MutableStateFlow(FullAppUiState())
    val state: StateFlow<FullAppUiState> = mutableState.asStateFlow()
    private var source: UploadSource = sample
    private var workJob: Job? = null

    fun configure(apiKey: String) {
        val clean = apiKey.trim()
        if (clean.isBlank()) {
            mutableState.update { it.copy(error = "A runtime API key is required.") }
            return
        }
        workJob?.cancel()
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

    fun resolveIdentity() = runAction(FullAppAction.IDENTITY) {
        val output = withContext(Dispatchers.IO) { repo.resolveIdentity() }
        mutableState.update { state ->
            val selected = if (output.collections.isNotEmpty() && state.collection !in output.collections) {
                output.collections.first()
            } else {
                state.collection
            }
            state.copy(
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
        mutableState.update { resetScope(it).copy(collection = value) }
    }

    fun setStream(value: String) {
        mutableState.update { resetScope(it).copy(stream = value) }
    }

    fun setQuery(value: String) {
        mutableState.update { it.copy(query = value) }
    }

    fun selectVideo(value: UploadSource) {
        source = value
        mutableState.update {
            resetUpload(it).copy(
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
                    results = emptyList(),
                    status = "Upload complete: $fileName. Create its index next.",
                )
            }
        }
    }

    fun cancelUpload() {
        workJob?.cancel()
        mutableState.update { it.copy(action = null, status = "Upload canceled.") }
    }

    fun createIndex() {
        val state = mutableState.value
        val error = strValidation(state, requireQuery = false)
        if (error.isNotBlank()) return showError(error)
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
        runAction(FullAppAction.SEARCH) {
            val output = withContext(Dispatchers.IO) {
                repo.search(state.query.trim(), state.collection.trim(), state.stream.trim())
            }
            val names = repo.lastCollections
            mutableState.update {
                it.copy(
                    action = null,
                    collectionsLoaded = true,
                    collections = names,
                    searched = true,
                    results = output.items,
                    status = "Search returned ${output.count} items from ${state.collection.trim()}/${state.stream.trim()}.",
                )
            }
        }
    }

    fun forgetApiKey() {
        workJob?.cancel()
        repo.clearCredentials()
        source = sample
        mutableState.value = FullAppUiState()
    }

    private fun runAction(action: FullAppAction, task: suspend () -> Unit) {
        if (!mutableState.value.configured) return showError("Configure the client first.")
        workJob?.cancel()
        workJob = viewModelScope.launch {
            mutableState.update { it.copy(action = action, error = "") }
            try {
                task()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(action = null, error = strUserError(error, it.collection))
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
        results = emptyList(),
    )

    override fun onCleared() {
        workJob?.cancel()
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

private class FullAppRepository {
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
            groupName = collection,
            streamName = stream,
            searchSources = listOf("image"),
            limit = 5,
            versionLancedb = version,
        )
        val items = response.data.mapNotNull(::strMap).take(5).mapIndexed { index, row ->
            SearchItem(
                id = "${row["item_id"] ?: row["filename"] ?: index}-$index",
                title = strFirst(row, "effective_title", "title", "item_id", "text_agg_tok")
                    .ifBlank { "Untitled result" },
                details = strDetails(row),
            )
        }
        return SearchOutput(items, response.cntActual)
    }

    fun clearCredentials() {
        keys?.clear()
        keys = null
        sdk = null
        lastCollections = emptyList()
    }

    private fun requireClient(): Client = requireNotNull(sdk) { "Configure the client first." }

    private fun strMap(value: Any?): Map<String, Any?>? =
        (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }

    private fun strFirst(row: Map<String, Any?>, vararg names: String): String =
        names.firstNotNullOfOrNull { name -> row[name]?.toString()?.trim()?.takeIf { it.isNotEmpty() } }
            .orEmpty()

    private fun strDetails(row: Map<String, Any?>): String {
        val values = mutableListOf<String>()
        strFirst(row, "source").takeIf { it.isNotEmpty() }?.let { values += it.uppercase() }
        strFirst(row, "ts_unix").takeIf { it.isNotEmpty() }?.let { values += "timestamp $it" }
        (row["score_ui"] as? Number)?.let { values += "score ${"%.1f".format(it.toDouble() * 100)}%" }
        return values.ifEmpty { listOf("Search result") }.joinToString(" • ")
    }
}
