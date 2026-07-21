package com.vmodal.sdk.examples

import com.vmodal.sdk.Client
import com.vmodal.sdk.UploadResponse
import java.io.File

// The legacy multipart endpoint is appropriate only for small files.
fun uploadSmallVideo(sdk: Client, file: File): UploadResponse =
    sdk.collections.uploadFile(
        file = file,
        groupName = "field-tests",
        streamName = "astream",
        description = "Short calibration clip",
        tag = listOf("calibration", "mobile"),
    )
