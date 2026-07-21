package com.vmodal.sdk.examples

import com.vmodal.sdk.Client

data class OperationsSnapshot(
    val usageTotal: Int,
    val userCount: Int,
    val cacheEntries: Int,
    val uploadKey: String,
)

// Admin calls require an account with the corresponding permissions.
fun operationsSnapshot(sdk: Client): OperationsSnapshot {
    val usage = sdk.admin.usage()
    val users = sdk.admin.userStats()
    val cache = sdk.admin.cacheStats()
    val upload = sdk.r2.presignUploadFile(
        mode = "vid_file",
        groupName = "field-tests",
        streamName = "astream",
        modality = "vid_raw",
        filename = "camera-01.mp4",
    )
    return OperationsSnapshot(
        usageTotal = usage.total,
        userCount = users.total,
        cacheEntries = cache.apikeyCacheSize,
        uploadKey = upload.key,
    )
}
