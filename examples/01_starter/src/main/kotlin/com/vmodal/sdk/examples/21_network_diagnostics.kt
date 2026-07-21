package com.vmodal.sdk.examples

import android.util.Log
import com.vmodal.sdk.AndroidLogWriter
import com.vmodal.sdk.AndroidLogcatDiagnostics
import com.vmodal.sdk.Client
import com.vmodal.sdk.SdkConfig
import com.vmodal.sdk.SdkDiagnostics

// Diagnostics are opt-in. The SDK sanitizes and bounds every event before this writer runs.
fun createDiagnosticClient(apiToken: String): Client {
    val logcat = AndroidLogcatDiagnostics(
        AndroidLogWriter { priority, tag, message -> Log.println(priority, tag, message) }
    )
    val cfg = SdkConfig(token = apiToken).withDiagnostics(
        SdkDiagnostics.enabled(logcat, responsePreviewLimit = 256)
    )
    return Client(cfg)
}
