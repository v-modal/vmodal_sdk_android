package com.vmodal.sdk.examples

import android.content.Context
import com.vmodal.sdk.Client
import com.vmodal.sdk.FileUploadSessionStore
import com.vmodal.sdk.UploadHandle
import com.vmodal.sdk.UploadSource
import com.vmodal.sdk.VideoUploadOptions
import com.vmodal.sdk.videoUploadAsync
import java.io.File

fun uploadWithProcessResume(sdk: Client, context: Context, video: File): UploadHandle {
    val options = VideoUploadOptions(
        multipart = true, // Experimental: the gateway must expose every multipart route.
        resume = true,
        sessionStore = FileUploadSessionStore(
            File(context.noBackupFilesDir, "vmodal-upload-checkpoints")
        ),
    )
    return sdk.collections.videoUploadAsync(
        source = UploadSource.fromFile(video),
        collectionName = "long-recordings",
        subCollectionName = "astream",
        options = options,
        onSuccess = { println("resumed=${it.resumed} path=${it.destPath}") },
        onFailure = { it.printStackTrace() },
    )
}
