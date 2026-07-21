package com.vmodal.sdk.examples

import android.content.Context
import android.net.Uri
import com.vmodal.sdk.UploadSource

fun contentUriSource(context: Context, uri: Uri, fileName: String): UploadSource {
    val resolver = context.contentResolver
    val size = resolver.openAssetFileDescriptor(uri, "r")!!.use { descriptor ->
        require(descriptor.length >= 0) { "Content URI must report its size" }
        descriptor.length
    }
    return UploadSource(
        fileName = fileName,
        contentLength = size,
        contentType = resolver.getType(uri) ?: "application/octet-stream",
        sourceId = uri.toString(),
    ) {
        resolver.openInputStream(uri) ?: error("Cannot open $uri")
    }
}
