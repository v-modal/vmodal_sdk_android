package com.vmodal.sdk

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CancellationException

/** Maximum response-preview length accepted by [SdkDiagnostics]. */
const val DIAGNOSTIC_PREVIEW_MAX_CHARS = 4_096

/** Network boundary observed by a [DiagnosticEvent]. */
enum class DiagnosticTransportKind {
    /** Main gateway or trusted direct API request. */
    GATEWAY,

    /** Credential-authenticated users API request. */
    USERS_API,

    /** Pre-authorized object-storage upload request. */
    SIGNED_UPLOAD,
}

/** Stable, message-free reason for a failed network attempt. */
enum class DiagnosticFailureCategory {
    /** The attempt exceeded its time limit. */
    TIMEOUT,

    /** The caller cancelled the active attempt. */
    CANCELLED,

    /** The transport failed before a usable response was available. */
    TRANSPORT,

    /** The bounded response limit was exceeded. */
    RESPONSE_TOO_LARGE,

    /** A response could not be decoded under the SDK contract. */
    MALFORMED_RESPONSE,

    /** Authentication was rejected. */
    AUTHENTICATION,

    /** Request or response validation failed. */
    VALIDATION,

    /** The API returned another unsuccessful result. */
    API,
}

/** Safe request-shape metadata. It never retains request values or uploaded bytes. */
class DiagnosticRequestMetadata internal constructor(
    /** Non-sensitive query, JSON, and form field names. */
    val fieldNames: List<String>,
    /** Sanitized basename-only upload filenames. */
    val fileNames: List<String>,
    /** Sanitized upload media type, when applicable. */
    val contentType: String,
    /** Upload byte offset, or `-1` when not applicable. */
    val offsetBytes: Long,
    /** Upload byte count, or `-1` when not applicable. */
    val lengthBytes: Long,
    /** Whether the expected response is text or binary. */
    val responseMode: String,
) {
    /** Returns only sanitized scalar metadata. */
    override fun toString(): String =
        "DiagnosticRequestMetadata(fieldNames=$fieldNames, fileNames=$fileNames, contentType=$contentType, " +
            "offsetBytes=$offsetBytes, lengthBytes=$lengthBytes, responseMode=$responseMode)"
}

/** Immutable, sanitized network attempt event delivered to a [DiagnosticSink]. */
sealed class DiagnosticEvent protected constructor(
    /** Local-only logical request identifier. */
    val correlationId: String,
    /** Network boundary used by this attempt. */
    val transportKind: DiagnosticTransportKind,
    /** Uppercase HTTP method. */
    val method: String,
    /** One-based attempt number under [correlationId]. */
    val attempt: Int,
    /** Fixed gateway label or sanitized signed-upload origin. */
    val target: String,
    /** Monotonic elapsed time for this attempt; zero on request start. */
    val elapsedMillis: Long,
    /** Sanitized request-shape metadata. */
    val request: DiagnosticRequestMetadata,
) {
    /** An HTTP attempt is about to start. */
    class RequestStarted internal constructor(
        correlationId: String,
        transportKind: DiagnosticTransportKind,
        method: String,
        attempt: Int,
        target: String,
        request: DiagnosticRequestMetadata,
    ) : DiagnosticEvent(correlationId, transportKind, method, attempt, target, 0, request) {
        /** Returns only sanitized event fields. */
        override fun toString(): String = strEvent("request-started")
    }

    /** An HTTP attempt returned a successful response. */
    class ResponseReceived internal constructor(
        correlationId: String,
        transportKind: DiagnosticTransportKind,
        method: String,
        attempt: Int,
        target: String,
        elapsedMillis: Long,
        request: DiagnosticRequestMetadata,
        /** HTTP status code. */
        val statusCode: Int,
        /** Sanitized response media type. */
        val contentType: String,
        /** Observed response byte count, or `-1` when unavailable. */
        val byteCount: Long,
        /** Redacted and bounded text preview, or an empty string. */
        val preview: String,
    ) : DiagnosticEvent(correlationId, transportKind, method, attempt, target, elapsedMillis, request) {
        /** Returns only sanitized event fields. */
        override fun toString(): String =
            strEvent("response", "statusCode=$statusCode, contentType=$contentType, byteCount=$byteCount, preview=$preview")
    }

    /** An HTTP attempt ended without a successful response. */
    class RequestFailed internal constructor(
        correlationId: String,
        transportKind: DiagnosticTransportKind,
        method: String,
        attempt: Int,
        target: String,
        elapsedMillis: Long,
        request: DiagnosticRequestMetadata,
        /** Stable failure classification without exception text or a cause. */
        val category: DiagnosticFailureCategory,
    ) : DiagnosticEvent(correlationId, transportKind, method, attempt, target, elapsedMillis, request) {
        /** Returns only sanitized event fields. */
        override fun toString(): String = strEvent("failure", "category=$category")
    }

    internal fun strEvent(name: String, extra: String = ""): String = buildString {
        append("DiagnosticEvent(type=").append(name)
        append(", correlationId=").append(correlationId)
        append(", transportKind=").append(transportKind)
        append(", method=").append(method)
        append(", attempt=").append(attempt)
        append(", target=").append(target)
        append(", elapsedMillis=").append(elapsedMillis)
        append(", request=").append(request)
        if (extra.isNotEmpty()) append(", ").append(extra)
        append(')')
    }
}

/** Consumer callback for already-sanitized diagnostic events. */
fun interface DiagnosticSink {
    /** Receives one immutable event. Sink failures are isolated by the SDK. */
    fun emit(event: DiagnosticEvent)
}

/** Opt-in network diagnostics configuration. Disabled diagnostics are the default. */
class SdkDiagnostics private constructor(
    internal val sink: DiagnosticSink?,
    /** Maximum text-response preview length, or zero to disable previews. */
    val responsePreviewLimit: Int,
    internal val clockNanos: () -> Long,
    internal val correlationIds: () -> String,
) {
    /** Whether a diagnostic sink is configured. */
    val enabled: Boolean get() = sink != null

    init {
        if (responsePreviewLimit != 0 && responsePreviewLimit !in MIN_PREVIEW_CHARS..DIAGNOSTIC_PREVIEW_MAX_CHARS) {
            throw ValidationFailed(
                "diagnostic response preview limit must be zero or $MIN_PREVIEW_CHARS..$DIAGNOSTIC_PREVIEW_MAX_CHARS"
            )
        }
    }

    /** Returns structural state without retaining or printing the sink. */
    override fun toString(): String =
        "SdkDiagnostics(enabled=$enabled, responsePreviewLimit=$responsePreviewLimit)"

    /** Factory methods for disabled and explicitly enabled diagnostics. */
    companion object {
        /** Returns the no-op default configuration. */
        fun disabled(): SdkDiagnostics = SdkDiagnostics(null, 0, System::nanoTime) { UUID.randomUUID().toString() }

        /** Enables sanitized events, with response previews disabled unless explicitly bounded. */
        fun enabled(sink: DiagnosticSink, responsePreviewLimit: Int = 0): SdkDiagnostics =
            SdkDiagnostics(sink, responsePreviewLimit, System::nanoTime) { UUID.randomUUID().toString() }

        internal fun test(
            sink: DiagnosticSink,
            responsePreviewLimit: Int = 0,
            clockNanos: () -> Long,
            correlationIds: () -> String,
        ): SdkDiagnostics = SdkDiagnostics(sink, responsePreviewLimit, clockNanos, correlationIds)
    }
}

internal data class DiagnosticAttempt(
    val correlationId: String,
    val transportKind: DiagnosticTransportKind,
    val method: String,
    val attempt: Int,
    val target: String,
    val request: DiagnosticRequestMetadata,
    val startedNanos: Long,
)

internal fun SdkDiagnostics.strCorrelationId(): String =
    if (!enabled) "" else strSafeScalar(correlationIds(), 80).ifBlank { UUID.randomUUID().toString() }

internal fun SdkDiagnostics.startAttempt(
    correlationId: String,
    transportKind: DiagnosticTransportKind,
    method: String,
    attempt: Int,
    target: String,
    request: DiagnosticRequestMetadata,
): DiagnosticAttempt? {
    if (!enabled) return null
    val item = DiagnosticAttempt(
        strSafeScalar(correlationId, 80),
        transportKind,
        strSafeMethod(method),
        attempt.coerceAtLeast(1),
        strSafeScalar(target, 512),
        request,
        clockNanos(),
    )
    emitSafely(DiagnosticEvent.RequestStarted(
        item.correlationId, item.transportKind, item.method, item.attempt, item.target, item.request
    ))
    return item
}

internal fun SdkDiagnostics.response(
    item: DiagnosticAttempt?,
    statusCode: Int,
    contentType: String,
    byteCount: Long,
    body: String = "",
) {
    if (item == null) return
    val type = strSafeContentType(contentType)
    val preview = if (responsePreviewLimit > 0 && strIsTextContent(type)) {
        strDiagnosticPreview(body, responsePreviewLimit)
    } else {
        ""
    }
    emitSafely(DiagnosticEvent.ResponseReceived(
        item.correlationId,
        item.transportKind,
        item.method,
        item.attempt,
        item.target,
        elapsedMillis(item.startedNanos),
        item.request,
        statusCode,
        type,
        byteCount.coerceAtLeast(-1),
        preview,
    ))
}

internal fun SdkDiagnostics.failure(item: DiagnosticAttempt?, error: Throwable) {
    if (item == null) return
    emitSafely(DiagnosticEvent.RequestFailed(
        item.correlationId,
        item.transportKind,
        item.method,
        item.attempt,
        item.target,
        elapsedMillis(item.startedNanos),
        item.request,
        diagnosticFailureCategory(error),
    ))
}

private fun SdkDiagnostics.elapsedMillis(startedNanos: Long): Long =
    ((clockNanos() - startedNanos).coerceAtLeast(0) / 1_000_000)

private fun SdkDiagnostics.emitSafely(event: DiagnosticEvent) {
    try {
        sink?.emit(event)
    } catch (_: Throwable) {
        // A diagnostic observer is never part of request success, failure, retry, or cancellation.
    }
}

internal fun diagnosticRequestMetadata(
    json: Any?,
    data: Map<String, Any?>,
    params: Map<String, Any?>,
    files: List<VmodalFilePart>,
    responseMode: VmodalResponseMode,
): DiagnosticRequestMetadata {
    val names = linkedSetOf<String>()
    collectSafeFieldNames(params, names)
    collectSafeFieldNames(data, names)
    collectSafeFieldNames(json, names)
    val fileNames = files.take(MAX_DIAGNOSTIC_ITEMS).map { strSafeFileName(it.fileName) }
    val length = files.fold(0L) { total, file ->
        if (Long.MAX_VALUE - total < file.contentLength) Long.MAX_VALUE else total + file.contentLength
    }
    return DiagnosticRequestMetadata(
        Collections.unmodifiableList(names.take(MAX_DIAGNOSTIC_ITEMS).toList()),
        Collections.unmodifiableList(fileNames),
        files.firstOrNull()?.let { strSafeContentType(it.contentType) }.orEmpty(),
        -1,
        length.takeIf { files.isNotEmpty() } ?: -1,
        responseMode.name.lowercase(),
    )
}

internal fun diagnosticRequestMetadataSafely(
    json: Any?,
    data: Map<String, Any?>,
    params: Map<String, Any?>,
    files: List<VmodalFilePart>,
    responseMode: VmodalResponseMode,
): DiagnosticRequestMetadata = try {
    diagnosticRequestMetadata(json, data, params, files, responseMode)
} catch (_: Throwable) {
    DiagnosticRequestMetadata(emptyList(), emptyList(), "", -1, -1, responseMode.name.lowercase())
}

internal fun diagnosticUploadMetadata(
    source: UploadSource,
    offset: Long,
    length: Long,
): DiagnosticRequestMetadata = DiagnosticRequestMetadata(
    emptyList(),
    listOf(strSafeFileName(source.fileName)),
    strSafeContentType(source.contentType),
    offset.coerceAtLeast(0),
    length.coerceAtLeast(0),
    "binary",
)

internal fun strDiagnosticOrigin(url: URL): String {
    val host = strSafeScalar(url.host.lowercase(), 255)
    val port = if (url.port >= 0 && url.port != url.defaultPort) ":${url.port}" else ""
    return "${url.protocol.lowercase()}://$host$port"
}

internal fun strResponseContentType(headers: Map<String, List<String>>): String =
    headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.firstOrNull().orEmpty()

internal fun diagnosticFailureCategory(error: Throwable): DiagnosticFailureCategory = when (error) {
    is CancellationException, is InterruptedException -> DiagnosticFailureCategory.CANCELLED
    is SocketTimeoutException -> DiagnosticFailureCategory.TIMEOUT
    is ResponseTooLarge -> DiagnosticFailureCategory.RESPONSE_TOO_LARGE
    is MalformedResponse -> DiagnosticFailureCategory.MALFORMED_RESPONSE
    is AuthError -> DiagnosticFailureCategory.AUTHENTICATION
    is ValidationFailed -> DiagnosticFailureCategory.VALIDATION
    is ApiError -> DiagnosticFailureCategory.API
    is TransportError -> if (error.cause is SocketTimeoutException) {
        DiagnosticFailureCategory.TIMEOUT
    } else {
        DiagnosticFailureCategory.TRANSPORT
    }
    is IOException -> DiagnosticFailureCategory.TRANSPORT
    else -> DiagnosticFailureCategory.TRANSPORT
}

internal fun strDiagnosticPreview(text: String, limit: Int): String {
    if (text.isEmpty() || limit <= 0) return ""
    val safe = try {
        val value = VmodalJson.parse(text)
        VmodalJson.stringify(diagnosticJsonValue(value, 0))
    } catch (_: SdkError) {
        strScrubSecrets(text)
    }
    return strBoundCodePoints(safe, limit)
}

private fun diagnosticJsonValue(value: Any?, depth: Int): Any? {
    if (depth >= MAX_DIAGNOSTIC_DEPTH) return "[bounded]"
    return when (value) {
        is Map<*, *> -> value.entries.take(MAX_DIAGNOSTIC_ITEMS).associate { entry ->
            val key = strSafeScalar(entry.key.toString(), MAX_METADATA_CHARS)
            key to if (isSensitiveName(key)) "[redacted]" else diagnosticJsonValue(entry.value, depth + 1)
        }
        is Iterable<*> -> value.take(MAX_DIAGNOSTIC_ITEMS).map { diagnosticJsonValue(it, depth + 1) }
        is Array<*> -> value.take(MAX_DIAGNOSTIC_ITEMS).map { diagnosticJsonValue(it, depth + 1) }
        is String -> strSafeScalar(strScrubSecrets(value), MAX_PREVIEW_VALUE_CHARS)
        null, is Number, is Boolean -> value
        else -> strSafeScalar(strScrubSecrets(value.toString()), MAX_PREVIEW_VALUE_CHARS)
    }
}

private fun collectSafeFieldNames(value: Any?, out: MutableSet<String>, depth: Int = 0) {
    if (depth >= MAX_DIAGNOSTIC_DEPTH || out.size >= MAX_DIAGNOSTIC_ITEMS) return
    when (value) {
        is Map<*, *> -> value.entries.forEach { entry ->
            val key = strSafeScalar(entry.key.toString(), MAX_METADATA_CHARS)
            if (!isSensitiveName(key) && key.isNotEmpty() && out.size < MAX_DIAGNOSTIC_ITEMS) out += key
            collectSafeFieldNames(entry.value, out, depth + 1)
        }
        is Iterable<*> -> value.take(MAX_DIAGNOSTIC_ITEMS).forEach { collectSafeFieldNames(it, out, depth + 1) }
        is Array<*> -> value.take(MAX_DIAGNOSTIC_ITEMS).forEach { collectSafeFieldNames(it, out, depth + 1) }
    }
}

private fun isSensitiveName(name: String): Boolean {
    val key = name.lowercase().filter(Char::isLetterOrDigit)
    return SENSITIVE_NAMES.any { it in key } || key.startsWith("xamz") || key.startsWith("xgoog")
}

private fun strScrubSecrets(value: String): String {
    var out = value.replace(URL_PATTERN, "[redacted-url]")
    out = out.replace(AUTH_PATTERN, "$1 [redacted]")
    out = out.replace(SECRET_PAIR_PATTERN, "$1=[redacted]")
    out = out.replace(PERCENT_SECRET_PATTERN, "[redacted]")
    return out.replace(Regex("[\\r\\n\\u0000-\\u001f\\u007f]+"), " ")
}

private fun strSafeMethod(value: String): String =
    value.uppercase().filter { it in 'A'..'Z' }.take(16).ifBlank { "UNKNOWN" }

private fun strSafeContentType(value: String): String {
    val type = value.substringBefore(';').trim().lowercase()
    return if (CONTENT_TYPE_PATTERN.matches(type)) type.take(MAX_METADATA_CHARS) else ""
}

private fun strIsTextContent(value: String): Boolean =
    value.startsWith("text/") || value == "application/json" || value.endsWith("+json")

private fun strSafeFileName(value: String): String {
    val base = value.replace('\\', '/').substringAfterLast('/').ifBlank { "file" }
    return strSafeScalar(strScrubSecrets(base), MAX_FILE_NAME_CHARS).ifBlank { "file" }
}

internal fun strBoundCodePoints(value: String, limit: Int, marker: String = "[truncated]"): String {
    if (limit <= 0) return ""
    val count = value.codePointCount(0, value.length)
    if (count <= limit) return value
    val markerCount = marker.codePointCount(0, marker.length)
    val keep = (limit - markerCount).coerceAtLeast(0)
    val end = value.offsetByCodePoints(0, keep)
    return value.substring(0, end) + marker
}

private fun strSafeScalar(value: String, limit: Int): String =
    strBoundCodePoints(value.replace(Regex("[\\u0000-\\u001f\\u007f]"), " ").trim(), limit)

private const val MIN_PREVIEW_CHARS = 64
private const val MAX_METADATA_CHARS = 96
private const val MAX_FILE_NAME_CHARS = 160
private const val MAX_PREVIEW_VALUE_CHARS = 1_024
private const val MAX_DIAGNOSTIC_DEPTH = 8
private const val MAX_DIAGNOSTIC_ITEMS = 32

private val SENSITIVE_NAMES = setOf(
    "authorization", "proxyauthorization", "apikey", "cookie", "setcookie", "token",
    "credential", "password", "secret", "signature", "sig", "key", "policy", "session", "expires",
)
private val CONTENT_TYPE_PATTERN = Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")
private val URL_PATTERN = Regex("(?i)https?://[^\\s\\\"'<>]+")
private val AUTH_PATTERN = Regex("(?i)\\b(bearer|basic)\\s+[a-z0-9._~+/=-]+")
private val SECRET_PAIR_PATTERN = Regex(
    "(?i)\\b(authorization|proxy[-_ ]?authorization|api[-_ ]?key|cookie|set[-_ ]?cookie|token|credential|password|secret|signature|sig|key|policy|session|expires|x[-_ ]?amz[-_ ]?[a-z0-9_-]+|x[-_ ]?goog[-_ ]?[a-z0-9_-]+)\\s*[:=]\\s*[^,;\\s&}\\]]+"
)
private val PERCENT_SECRET_PATTERN = Regex(
    "(?i)(authorization|api(?:%2d|%5f|[-_])?key|token|credential|password|secret|signature|sig|key|expires|x(?:%2d|[-_])?amz)[^%\\s]{0,20}(?:%3d|=)[^&\\s]+"
)
