package com.vmodal.sdk.examples

import com.vmodal.sdk.Client
import com.vmodal.sdk.MetadataParquetUploadResponse
import com.vmodal.sdk.filePart
import java.io.File

fun uploadMetadata(sdk: Client, jsonl: File): MetadataParquetUploadResponse =
    sdk.collections.uploadMetadataJsonl(
        part = filePart("file", jsonl, "application/json"),
        mode = "img_file",
        groupName = "product-images",
        streamName = "astream",
        writeMode = "append",
        allowOverlap = false,
    )
