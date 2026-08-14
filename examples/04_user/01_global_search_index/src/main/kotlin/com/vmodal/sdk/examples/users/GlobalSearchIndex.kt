package com.vmodal.sdk.examples.users

import com.vmodal.sdk.SearchResponse
import com.vmodal.sdk.UploadSource
import com.vmodal.sdk.VModal
import com.vmodal.sdk.VideoUploadResponse

/**
 * Google Search-style organization with one shared application index.
 *
 * Backend collection: `video_search__global`
 * Stream: `uploads`
 */
class GlobalSearchIndex(apiKey: String) {
    private val search = VModal.configure(
        projectId = "video_search",
        apiKey = apiKey,
    ).scope(
        collectionName = "global",
        streamName = "uploads",
    )

    suspend fun upload(video: UploadSource): VideoUploadResponse =
        search.upload(video)

    suspend fun search(query: String = "red bicycle near a bridge"): SearchResponse =
        search.search(query)
}
