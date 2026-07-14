package com.vmodal.sdk.examples

import com.vmodal.sdk.Client

fun mutateCollection(sdk: Client): List<Map<String, Any?>> {
    val added = sdk.collections.addAssets(
        collectionId = "collection-id",
        assetIds = listOf("asset-001", "asset-002"),
        mode = "vid_file",
        groupName = "field-tests",
    )
    val updated = sdk.collections.updateDescription(
        groupName = "field-tests",
        mode = "vid_file",
        streamName = "astream",
        filenameSanitized = "camera-01.mp4",
        description = "North entrance camera",
        tag = listOf("entrance", "night"),
    )
    val deletePreview = sdk.collections.delete(
        groupName = "field-tests",
        mode = "vid_file",
        dryRun = true,
        confirm = false,
    )
    return listOf(added.raw, updated.raw, deletePreview.raw)
}
