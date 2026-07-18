package com.vmodal.sdk.examples.search

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.vmodal.sdk.UploadSource

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
        contentType = resolver.getType(uri) ?: "application/octet-stream",
        sourceId = uri.toString(),
    ) {
        resolver.openInputStream(uri) ?: error("Cannot open the selected video.")
    }
}
