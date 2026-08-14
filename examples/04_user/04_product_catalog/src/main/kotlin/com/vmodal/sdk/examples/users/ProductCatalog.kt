package com.vmodal.sdk.examples.users

import com.vmodal.sdk.SearchResponse
import com.vmodal.sdk.UploadSource
import com.vmodal.sdk.VModal
import com.vmodal.sdk.VideoUploadResponse

/**
 * Commerce organization based on a business domain rather than an end user.
 *
 * Backend collection: `shopping_app__product_catalog`
 * Stream: `merchant_uploads`
 */
class ProductCatalog(apiKey: String) {
    private val catalog = VModal.configure(
        projectId = "shopping_app",
        apiKey = apiKey,
    ).scope(
        collectionName = "product_catalog",
        streamName = "merchant_uploads",
    )

    suspend fun upload(asset: UploadSource): VideoUploadResponse =
        catalog.upload(asset)

    suspend fun search(query: String): SearchResponse =
        catalog.search(query)
}
