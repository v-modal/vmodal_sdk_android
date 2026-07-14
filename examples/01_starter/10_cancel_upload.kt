package com.vmodal.sdk.examples

import com.vmodal.sdk.Client
import com.vmodal.sdk.UploadHandle
import com.vmodal.sdk.UploadSource
import com.vmodal.sdk.videoUploadAsync
import java.io.File

class UploadOwner(private val sdk: Client) {
    private var handle: UploadHandle? = null

    fun start(file: File) {
        handle = sdk.collections.videoUploadAsync(
            source = UploadSource.fromFile(file),
            collectionName = "field-tests",
            subCollectionName = "astream",
            onSuccess = { println(it.destPath) },
            onFailure = { it.printStackTrace() },
        )
    }

    fun stop() {
        handle?.cancel()
        handle = null
    }
}
