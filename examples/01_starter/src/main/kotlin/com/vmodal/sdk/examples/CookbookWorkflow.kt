package com.vmodal.sdk.examples

import com.vmodal.sdk.Client
import com.vmodal.sdk.ImageUrlRecord
import com.vmodal.sdk.IndexationStatusResponse
import com.vmodal.sdk.IndexationSubmitResponse
import com.vmodal.sdk.SearchResponse
import com.vmodal.sdk.UploadProgress
import com.vmodal.sdk.UploadSource
import com.vmodal.sdk.VideoUploadResponse
import kotlinx.coroutines.delay

/** Immutable coordinates shared by upload, index creation, search, and image mapping. */
data class CookbookScope(
    val collection: String,
    val stream: String,
    val mode: String = "vid_file",
    val indexVersion: String = "new_version",
    val searchSources: List<String> = listOf("ocr", "asr", "image"),
)

/** Scope after a successful index job advertises a numeric LanceDB version. */
data class ReadyCookbookScope(
    val operation: CookbookScope,
    val lancedbVersion: Int,
)

data class CookbookImage(
    val searchIndex: Int,
    val url: String,
)

data class CookbookSearchResult(
    val search: SearchResponse,
    val images: List<CookbookImage>,
    val skippedHits: Int,
    val unresolvedImages: Int,
)

suspend fun uploadCookbookVideo(
    client: Client,
    scope: CookbookScope,
    source: UploadSource,
    onProgress: (UploadProgress) -> Unit = {},
): VideoUploadResponse = client.coroutines().collections.videoUpload(
    source = source,
    collectionName = scope.collection,
    subCollectionName = scope.stream,
    mode = scope.mode,
    modality = "vid_raw",
    onProgress = onProgress,
)

suspend fun submitCookbookIndex(client: Client, scope: CookbookScope): IndexationSubmitResponse =
    client.coroutines().indexes.createIndex(
        mode = scope.mode,
        groupName = scope.collection,
        streamName = scope.stream,
        indexType = "vid_img_emb",
        modality = "vid_img_emb",
        version = scope.indexVersion,
        reProcess = true,
    )

suspend fun awaitCookbookIndex(
    client: Client,
    jobId: String,
    maxPolls: Int = 60,
    pollMillis: Long = 2_000,
): IndexationStatusResponse {
    require(maxPolls > 0) { "maxPolls must be positive" }
    repeat(maxPolls) { poll ->
        val value = client.coroutines().indexes.indexStatus(jobId)
        val status = value.status.trim().lowercase()
        if (status in INDEX_READY) return value
        check(status !in INDEX_FAILED) { "Index job ended in $status" }
        if (poll + 1 < maxPolls) delay(pollMillis)
    }
    error("Index job did not reach a terminal state within the polling bound")
}

suspend fun readyCookbookScope(client: Client, scope: CookbookScope): ReadyCookbookScope {
    val group = client.coroutines().collections.listGroups(scope.mode)
        .findGroup(scope.collection, scope.mode)
        ?: error("The collection is not available to the current credential")
    val version = group.latestLancedbVersion
        ?: error("The collection has no advertised searchable LanceDB version")
    return ReadyCookbookScope(scope, version)
}

suspend fun searchCookbookVideo(
    client: Client,
    scope: ReadyCookbookScope,
    query: String,
    queryMetadata: Map<String, Any?>? = null,
): CookbookSearchResult {
    val operation = scope.operation
    val api = client.coroutines()
    val search = api.searches.searchVideo(
        queryText = query,
        queryMetadata = queryMetadata,
        mode = operation.mode,
        groupName = operation.collection,
        streamName = operation.stream,
        searchSources = operation.searchSources,
        searchCombineMode = "union",
        offset = 0,
        limit = 50,
        versionLancedb = scope.lancedbVersion,
    )
    val candidates = search.data.mapIndexedNotNull { index, value ->
        val row = value.strMap() ?: return@mapIndexedNotNull null
        val fileName = row.strFirst("filename", "filename_sanitized", "title")
        if (fileName.isBlank()) return@mapIndexedNotNull null
        val stream = row.strFirst("stream_name", "stream").ifBlank { operation.stream }
        val stamp = row.strFirst("ts_unix_13digits", "ts_unix").ifBlank { null }
        index to ImageUrlRecord(
            mode = operation.mode,
            groupName = operation.collection,
            modality = "vid_img",
            streamName = stream,
            filename = fileName,
            tsUnix13digits = stamp,
        ).toMap()
    }
    if (candidates.isEmpty()) {
        return CookbookSearchResult(search, emptyList(), search.data.size, 0)
    }

    val seen = mutableSetOf<Int>()
    val images = api.images.getUrlBulk(candidates.map { it.second }).records.mapNotNull { row ->
        val inputIndex = row["input_index"].strIndex() ?: return@mapNotNull null
        if (inputIndex !in candidates.indices || !seen.add(inputIndex)) return@mapNotNull null
        if (row["found"] == false) return@mapNotNull null
        val url = row["url_pre_signed"]?.toString()?.trim().orEmpty()
        if (url.isBlank()) return@mapNotNull null
        CookbookImage(candidates[inputIndex].first, url)
    }.sortedBy { it.searchIndex }
    return CookbookSearchResult(
        search = search,
        images = images,
        skippedHits = search.data.size - candidates.size,
        unresolvedImages = candidates.size - images.size,
    )
}

private fun Any?.strMap(): Map<String, Any?>? =
    (this as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }

private fun Map<String, Any?>.strFirst(vararg names: String): String =
    names.firstNotNullOfOrNull { name -> this[name]?.toString()?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()

private fun Any?.strIndex(): Int? = when (this) {
    is Number -> toInt().takeIf { toDouble() == it.toDouble() }
    is String -> toIntOrNull()
    else -> null
}

private val INDEX_READY = setOf("success", "succeeded", "done", "completed", "ok")
private val INDEX_FAILED = setOf("failed", "failure", "error", "cancelled", "canceled", "dead_letter")
