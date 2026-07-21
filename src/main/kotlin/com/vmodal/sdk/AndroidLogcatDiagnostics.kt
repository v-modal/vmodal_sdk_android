package com.vmodal.sdk

/** Android-compatible log priority used without adding an Android runtime dependency to core. */
enum class DiagnosticLogLevel(/** Value accepted by `android.util.Log.println`. */ val priority: Int) {
    /** Debug priority. */
    DEBUG(3),

    /** Informational priority. */
    INFO(4),

    /** Warning priority. */
    WARNING(5),
}

/** Small boundary compatible with `android.util.Log.println(priority, tag, message)`. */
fun interface AndroidLogWriter {
    /** Writes one already-sanitized, bounded line and returns the platform result. */
    fun println(priority: Int, tag: String, message: String): Int
}

/**
 * [DiagnosticSink] that renders sanitized events as bounded Android Logcat lines.
 *
 * The application supplies [writer], normally by delegating to `android.util.Log.println`, so the
 * core SDK remains JVM-testable and has no hard Android framework dependency.
 */
class AndroidLogcatDiagnostics(
    private val writer: AndroidLogWriter,
    /** Stable Logcat tag. */
    val tag: String = "VModalSDK",
    /** Request-start severity. */
    val requestLevel: DiagnosticLogLevel = DiagnosticLogLevel.DEBUG,
    /** Successful-response severity. */
    val responseLevel: DiagnosticLogLevel = DiagnosticLogLevel.INFO,
    /** Failure severity. */
    val failureLevel: DiagnosticLogLevel = DiagnosticLogLevel.WARNING,
    /** Maximum rendered code points per line. */
    val lineLimit: Int = 4_000,
) : DiagnosticSink {
    init {
        if (tag.isBlank() || tag.length > 64 || tag.any(Char::isISOControl)) {
            throw ValidationFailed("diagnostic Logcat tag is invalid")
        }
        if (lineLimit !in 64..4_000) throw ValidationFailed("diagnostic Logcat line limit must be 64..4000")
    }

    /** Formats and writes one event without passing a throwable or response body to Logcat. */
    override fun emit(event: DiagnosticEvent) {
        val level = when (event) {
            is DiagnosticEvent.RequestStarted -> requestLevel
            is DiagnosticEvent.ResponseReceived -> responseLevel
            is DiagnosticEvent.RequestFailed -> failureLevel
        }
        writer.println(level.priority, tag, strBoundCodePoints(event.toString(), lineLimit))
    }

    /** Returns structural adapter state without retaining or rendering the writer. */
    override fun toString(): String =
        "AndroidLogcatDiagnostics(tag=$tag, requestLevel=$requestLevel, responseLevel=$responseLevel, " +
            "failureLevel=$failureLevel, lineLimit=$lineLimit)"
}
