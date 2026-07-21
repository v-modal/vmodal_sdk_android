package com.vmodal.sdk.examples

import com.vmodal.sdk.Client
import com.vmodal.sdk.UploadSource
import com.vmodal.sdk.VideoUploadResponse
import com.vmodal.sdk.videoUpload
import java.io.File

// Use from WorkManager.doWork() or another background worker, never the main thread.
fun uploadFromWorker(sdk: Client, file: File): VideoUploadResponse =
    sdk.collections.videoUpload(
        source = UploadSource.fromFile(file),
        collectionName = "nightly-ingest",
        subCollectionName = "astream",
        onProgress = { println("${it.uploadedBytes}/${it.totalBytes}") },
    )
