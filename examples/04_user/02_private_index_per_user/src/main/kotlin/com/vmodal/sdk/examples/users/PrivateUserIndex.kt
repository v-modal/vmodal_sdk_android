package com.vmodal.sdk.examples.users

import com.vmodal.sdk.SearchResponse
import com.vmodal.sdk.UploadSource
import com.vmodal.sdk.VModal
import com.vmodal.sdk.VideoUploadResponse

/**
 * One isolated searchable collection for an application end user.
 *
 * For `endUserId = "123"`, the backend collection is
 * `food_app__user_123`. The end-user identifier remains an application
 * concern expressed through `collectionName`.
 */
class PrivateUserIndex(
    apiKey: String,
    endUserId: String,
) {
    private val personal = VModal.configure(
        projectId = "food_app",
        apiKey = apiKey,
    ).scope(
        collectionName = userCollectionName(endUserId),
        streamName = "personal_videos",
    )

    suspend fun upload(video: UploadSource): VideoUploadResponse =
        personal.upload(video)

    suspend fun search(query: String = "pasta recipe"): SearchResponse =
        personal.search(query)
}

private fun userCollectionName(endUserId: String): String {
    val id = endUserId.trim()
    require(id.isNotEmpty()) { "endUserId is required" }
    return "user_$id"
}
