package com.vmodal.sdk.examples.search

import android.util.Log
import com.vmodal.sdk.AndroidLogWriter
import com.vmodal.sdk.AndroidLogcatDiagnostics
import com.vmodal.sdk.SdkDiagnostics

internal fun logcatDiagnostics(): SdkDiagnostics = SdkDiagnostics.enabled(
    AndroidLogcatDiagnostics(
        AndroidLogWriter { priority, tag, message -> Log.println(priority, tag, message) }
    ),
    responsePreviewLimit = 256,
)
