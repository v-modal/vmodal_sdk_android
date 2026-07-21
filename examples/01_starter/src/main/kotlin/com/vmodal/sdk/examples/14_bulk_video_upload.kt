package com.vmodal.sdk.examples

import com.vmodal.sdk.Client
import com.vmodal.sdk.UploadHandle
import com.vmodal.sdk.UploadSource
import com.vmodal.sdk.videoUploadBulkAsync
import java.io.File

fun uploadVideos(sdk: Client, files: List<File>): UploadHandle {
    val sources = files.map { UploadSource.fromFile(it) }
    return sdk.collections.videoUploadBulkAsync(
        sources = sources,
        collectionName = "batch-import",
        subCollectionName = "astream",
        onProgress = { println("batch=${it.percent}%") },
        onSuccess = { println("uploaded=${it.total}") },
        onFailure = { it.printStackTrace() },
    )
}
