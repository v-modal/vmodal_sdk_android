package com.vmodal.sdk.examples

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.vmodal.sdk.UploadSource

/** Retains read access when a picker contract permits durable background work. */
fun retainReadPermission(context: Context, uri: Uri) {
    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

/** Converts a reopenable content URI with a known non-empty size into an upload source. */
fun contentUriUploadSource(context: Context, uri: Uri): UploadSource {
    val app = context.applicationContext
    val resolver = app.contentResolver
    var fileName = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    var size = -1L

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
            if (sizeCol >= 0 && !cursor.isNull(sizeCol)) size = cursor.getLong(sizeCol)
        }
    }
    if (size < 0) size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    require(fileName.isNotBlank()) { "The selected content has no display name" }
    require(size > 0) { "The selected content provider must report a non-empty size" }

    return UploadSource(
        fileName = fileName,
        contentLength = size,
        contentType = resolver.getType(uri) ?: "application/octet-stream",
        sourceId = uri.toString(),
    ) {
        app.contentResolver.openInputStream(uri) ?: error("Unable to reopen selected content")
    }
}
