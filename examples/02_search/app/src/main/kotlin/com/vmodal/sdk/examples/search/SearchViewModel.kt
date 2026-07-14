package com.vmodal.sdk.examples.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vmodal.sdk.Client
import com.vmodal.sdk.MutableApiKeyProvider
import com.vmodal.sdk.PUBLIC_GATEWAY_URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SearchImage(
    val id: String,
    val url: String,
    val title: String,
    val filename: String,
)

data class SearchUiState(
    val loading: Boolean = false,
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

class SearchViewModel private constructor(private val repo: SearchRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = mutableState.asStateFlow()
    private var searchJob: Job? = null

    fun search(apiKey: String, query: String, group: String, stream: String) {
        val cleanKey = apiKey.trim()
        val cleanQuery = query.trim()
        val cleanGroup = group.trim()
        val cleanStream = stream.trim()
        val validation = when {
            cleanKey.isBlank() -> "Enter a runtime API key."
            cleanQuery.isBlank() -> "Enter search text."
            cleanGroup.isBlank() -> "Enter a collection name."
            cleanStream.isBlank() -> "Enter a stream name."
            else -> ""
        }
        if (validation.isNotBlank()) {
            mutableState.value = SearchUiState(searched = true, error = validation)
            return
        }

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            mutableState.value = SearchUiState(loading = true)
            try {
                val output = withContext(Dispatchers.IO) {
                    repo.search(cleanKey, cleanQuery, cleanGroup, cleanStream)
                }
                mutableState.value = SearchUiState(
                    searched = true,
                    images = output.images,
                    total = output.total,
                    elapsedMs = output.elapsedMs,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = SearchUiState(
                    searched = true,
                    error = e.message ?: "Search failed.",
                )
            }
        }
    }

    fun clearCredentials() {
        searchJob?.cancel()
        repo.clearCredentials()
        mutableState.value = SearchUiState()
    }

    override fun onCleared() {
        repo.clearCredentials()
        super.onCleared()
    }

    companion object {
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

    fun search(apiKey: String, query: String, group: String, stream: String): SearchOutput {
        val client = client(apiKey)
        val result = client.searches.searchVideo(
            queryText = query,
            mode = "vid_file",
            groupName = group,
            streamName = stream,
            searchSources = listOf("ocr", "asr", "image"),
            limit = 50,
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
            mode = "gateway",
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
