package com.vmodal.sdk.examples

import com.vmodal.sdk.Client
import com.vmodal.sdk.UploadHandle
import com.vmodal.sdk.UploadSource
import com.vmodal.sdk.videoUploadAsync
import java.io.File

fun uploadVideoAsync(sdk: Client, file: File): UploadHandle =
    sdk.collections.videoUploadAsync(
        source = UploadSource.fromFile(file),
        collectionName = "field-tests",
        subCollectionName = "astream",
        onProgress = { println("uploaded=${it.percent}%") },
        onSuccess = { println("destination=${it.destPath}") },
        onFailure = { it.printStackTrace() },
    )
