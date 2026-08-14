package com.vmodal.sdk.examples.users

import com.vmodal.sdk.VModal
import com.vmodal.sdk.VModalScope

/**
 * One user collection divided by asset source or purpose.
 *
 * For `endUserId = "123"`, every scope uses backend collection
 * `food_app__user_123`; only `streamName` changes.
 */
class UserStreams(
    apiKey: String,
    endUserId: String,
) {
    private val app = VModal.configure(
        projectId = "food_app",
        apiKey = apiKey,
    )
    private val collection = userStreamCollectionName(endUserId)

    val camera: VModalScope = app.scope(collection, "camera")
    val favorites: VModalScope = app.scope(collection, "favorites")
    val uploads: VModalScope = app.scope(collection, "uploads")
}

private fun userStreamCollectionName(endUserId: String): String {
    val id = endUserId.trim()
    require(id.isNotEmpty()) { "endUserId is required" }
    return "user_$id"
}
