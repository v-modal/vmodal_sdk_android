package com.vmodal.sdk.examples.app1

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.vmodal.sdk.UploadSource
import java.io.ByteArrayInputStream

const val BUNDLED_VIDEO = "kitchen.mp4"

fun assetUploadSource(context: Context, name: String): UploadSource {
    val data = context.assets.open(name).use { it.readBytes() }
    return UploadSource(
        fileName = name,
        contentLength = data.size.toLong(),
        contentType = "video/mp4",
        sourceId = "asset://$name",
    ) { ByteArrayInputStream(data) }
}

fun contentUriUploadSource(context: Context, uri: Uri): UploadSource {
    val resolver = context.contentResolver
    var fileName = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    var contentLength = -1L

    resolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameCol >= 0) fileName = cursor.getString(nameCol).orEmpty()
            if (sizeCol >= 0 && !cursor.isNull(sizeCol)) contentLength = cursor.getLong(sizeCol)
        }
    }

    if (contentLength < 0) {
        contentLength = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    }
    require(fileName.isNotBlank()) { "The selected video has no display name." }
    require(contentLength > 0) { "The selected video provider did not report a non-empty size." }

    return UploadSource(
        fileName = fileName,
        contentLength = contentLength,
        contentType = resolver.getType(uri) ?: "video/mp4",
        sourceId = uri.toString(),
    ) {
        resolver.openInputStream(uri) ?: error("Cannot open the selected video.")
    }
}
