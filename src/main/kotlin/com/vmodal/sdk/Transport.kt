package com.vmodal.sdk

import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Default maximum structured response size. */
const val JSON_RESPONSE_LIMIT_BYTES = 8L * 1024 * 1024
/** Default maximum error response size. */
const val ERROR_RESPONSE_LIMIT_BYTES = 1L * 1024 * 1024
/** Default maximum in-memory binary response size. */
const val BINARY_RESPONSE_LIMIT_BYTES = 64L * 1024 * 1024

/**
 * Reopenable multipart file part used by low-level collection helpers.
 *
 * @property fieldName multipart form field name
 * @property fileName transmitted file name
 * @property contentLength exact byte length
 * @property contentType media type sent for the part
 */
class VmodalFilePart(
    val fieldName: String,
    val fileName: String,
    val contentLength: Long,
    val contentType: String = "application/octet-stream",
    private val opener: () -> InputStream,
) {
    init {
        strMultipartValue("field name", fieldName, 128)
        strMultipartValue("file name", fileName, 1_024)
        strMultipartValue("content type", contentType, 255)
        if (contentLength < 0) throw ValidationFailed("content_length must not be negative")
    }

    // A reopenable stream keeps multipart uploads retryable without retaining the file in heap.
    // Android callers can pass { contentResolver.openInputStream(uri)!! } as the opener.
    /** Creates an in-memory part. Prefer the streaming constructor for large data. */
    constructor(fieldName: String, fileName: String, bytes: ByteArray, contentType: String = "application/octet-stream") :
        this(fieldName, fileName, bytes.size.toLong(), contentType, { bytes.inputStream() })

    /** Opens a new stream. The caller owns and closes the returned stream. */
    fun open(): InputStream = opener()
}

/**
 * Transport-neutral request value used by injectable [VmodalTransport] implementations.
 *
 * @property method HTTP method
 * @property path relative SDK path or validated absolute URL
 * @property queryParameters URL query values
 * @property headers request headers
 * @property jsonBody optional structured body
 * @property formFields optional form body
 * @property files optional multipart files
 */
data class VmodalRequest(
    val method: String,
    val path: String,
    val queryParameters: Map<String, Any?> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val jsonBody: Any? = null,
    val formFields: Map<String, Any?> = emptyMap(),
    val files: List<VmodalFilePart> = emptyList(),
) {
    internal var responseMode: VmodalResponseMode = VmodalResponseMode.TEXT

    /** Returns redacted request-shape diagnostics. */
    override fun toString(): String = buildString {
        append("VmodalRequest(method=").append(method)
        append(", pathType=").append(if (strIsAbsoluteHttpUrl(path)) "absolute" else "relative")
        append(", queryParameterKeys=").append(queryParameters.keys)
        append(", headerNames=").append(headers.keys)
        append(", hasJsonBody=").append(jsonBody != null)
        append(", formFieldNames=").append(formFields.keys)
        append(", fileCount=").append(files.size)
        append(')')
    }
}

/**
 * Bounded transport response with lazy structured decoding.
 *
 * @property statusCode HTTP response status
 * @property body decoded UTF-8 response text
 * @property headers response header values
 * @property bytes bounded raw response bytes
 */
data class VmodalResponse(
    val statusCode: Int,
    val body: String = "",
    val headers: Map<String, List<String>> = emptyMap(),
    val bytes: ByteArray = body.toByteArray(StandardCharsets.UTF_8),
) {
    /** Lazily decoded structured response value. */
    val json: Any? by lazy { if (body.isEmpty()) null else VmodalJson.parse(body) }

    @Suppress("UNCHECKED_CAST")
    /** Returns the response as an object or throws [MalformedResponse]. */
    fun jsonObject(): Map<String, Any?> {
        if (body.isEmpty()) return emptyMap()
        return json as? Map<String, Any?> ?: throw MalformedResponse("JSON object response required")
    }

    /** Returns response metadata without exposing its body. */
    override fun toString(): String =
        "VmodalResponse(statusCode=$statusCode, headerNames=${headers.keys}, bodyBytes=${bytes.size})"
}

/** Injectable synchronous transport contract. */
interface VmodalTransport {
    /** Executes [request] and returns a bounded response. */
    fun execute(request: VmodalRequest): VmodalResponse
}

internal enum class VmodalResponseMode { TEXT, BYTES }

/** Default synchronous JVM transport with strict bounds and redirect handling. */
class HttpUrlConnectionTransport private constructor(
    private val cfg: SdkConfig,
    private val jsonLimitBytes: Long,
    private val errorLimitBytes: Long,
    private val binaryLimitBytes: Long,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) : VmodalTransport {
    constructor(cfg: SdkConfig) : this(
        cfg,
        JSON_RESPONSE_LIMIT_BYTES,
        ERROR_RESPONSE_LIMIT_BYTES,
        BINARY_RESPONSE_LIMIT_BYTES,
        Unit,
    )

    constructor(
        cfg: SdkConfig,
        jsonLimitBytes: Long,
        errorLimitBytes: Long,
        binaryLimitBytes: Long,
    ) : this(cfg, jsonLimitBytes, errorLimitBytes, binaryLimitBytes, Unit)

    init {
        listOf(jsonLimitBytes, errorLimitBytes, binaryLimitBytes).forEach {
            if (it <= 0 || it > Int.MAX_VALUE) throw ValidationFailed("response limit is invalid")
        }
    }

    override fun execute(request: VmodalRequest): VmodalResponse {
        if (request.files.isNotEmpty()) request.validateMultipart()
        val base = if (strIsAbsoluteHttpUrl(request.path)) "" else cfg.normalizedBaseUrl
        val url = validatedHttpUrl(base + request.path + request.queryParameters.toQueryString())
        val boundary = "----vmodal-${UUID.randomUUID()}"
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.apply {
                instanceFollowRedirects = false
                requestMethod = request.method.uppercase()
                connectTimeout = cfg.timeoutMillis
                readTimeout = cfg.timeoutMillis
                setRequestProperty("Accept", "application/json")
                request.headers.forEach { (key, value) -> setRequestProperty(key, value) }
                when {
                    request.files.isNotEmpty() -> setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    request.formFields.isNotEmpty() -> setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    request.jsonBody != null -> setRequestProperty("Content-Type", "application/json")
                }
            }
            if (request.method.uppercase() !in listOf("GET", "HEAD") && request.hasBody()) {
                conn.doOutput = true
                conn.outputStream.use { out ->
                    when {
                        request.files.isNotEmpty() -> request.writeMultipart(out, boundary)
                        request.formFields.isNotEmpty() -> out.write(request.formFields.toQueryString(true).toByteArray(StandardCharsets.UTF_8))
                        request.jsonBody != null -> out.write(VmodalJson.stringify(request.jsonBody).toByteArray(StandardCharsets.UTF_8))
                    }
                }
            }
            val status = conn.responseCode
            val success = status in 200..299
            val limit = when {
                !success -> errorLimitBytes
                request.responseMode == VmodalResponseMode.BYTES -> binaryLimitBytes
                else -> jsonLimitBytes
            }
            val declared = responseContentLength(conn.getHeaderField("Content-Length"))
            val stream = if (success) conn.inputStream else conn.errorStream
            val bytes = stream?.use { input -> input.bytesBounded(declared, limit) } ?: ByteArray(0)
            val headers = conn.headerFields.entries.mapNotNull { (key, value) -> key?.let { it to value } }.toMap()
            val body = if (success && request.responseMode == VmodalResponseMode.BYTES) {
                ""
            } else {
                bytes.toString(StandardCharsets.UTF_8)
            }
            return VmodalResponse(status, body, headers, bytes)
        } finally {
            conn.disconnect()
        }
    }
}

internal fun responseContentLength(value: String?): Long {
    if (value.isNullOrBlank()) return -1
    val length = value.trim().toLongOrNull() ?: throw MalformedResponse("invalid Content-Length")
    if (length < 0) throw MalformedResponse("invalid Content-Length")
    return length
}

internal fun InputStream.bytesBounded(declaredLength: Long, limitBytes: Long): ByteArray {
    if (declaredLength < -1) throw MalformedResponse("invalid Content-Length")
    if (declaredLength > limitBytes) throw ResponseTooLarge(limitBytes, declaredLength)
    val initial = when {
        declaredLength in 0..8_192 -> declaredLength.toInt()
        else -> 8_192
    }
    val out = ByteArrayOutputStream(initial)
    val buf = ByteArray(16 * 1_024)
    var total = 0L
    while (true) {
        val count = read(buf)
        if (count < 0) break
        if (total > limitBytes - count) throw ResponseTooLarge(limitBytes, total + count)
        out.write(buf, 0, count)
        total += count
    }
    return out.toByteArray()
}

internal fun strIsAbsoluteHttpUrl(value: String): Boolean {
    val lower = value.trimStart().lowercase()
    return lower.startsWith("http://") || lower.startsWith("https://")
}

internal fun validatedHttpUrl(value: String): URL {
    val url = try {
        URL(value)
    } catch (exc: Exception) {
        throw ValidationFailed("invalid HTTP URL")
    }
    if (url.protocol !in listOf("http", "https")) throw ValidationFailed("HTTP or HTTPS URL is required")
    if (url.userInfo != null) throw ValidationFailed("URL user information is not allowed")
    if (url.protocol == "http" && !strIsLoopbackHost(url.host)) {
        throw ValidationFailed("HTTPS is required for non-local URLs")
    }
    return url
}

internal fun strRequireSameOrigin(path: String, baseUrl: String) {
    if (!strIsAbsoluteHttpUrl(path)) return
    val target = validatedHttpUrl(path)
    val base = validatedHttpUrl(baseUrl)
    if (target.protocol != base.protocol || !target.host.equals(base.host, true) || target.effectivePort() != base.effectivePort()) {
        throw ValidationFailed("absolute API URL must match the configured origin")
    }
}

private fun strIsLoopbackHost(value: String): Boolean = value.trim('[', ']').lowercase() in setOf(
    "localhost",
    "127.0.0.1",
    "::1",
    "0:0:0:0:0:0:0:1",
)

private fun URL.effectivePort(): Int = if (port >= 0) port else defaultPort

/** Creates a reopenable part backed by [file]. */
fun filePart(fieldName: String, file: File, contentType: String = guessContentType(file.name)): VmodalFilePart =
    VmodalFilePart(fieldName, file.name, file.length(), contentType) { file.inputStream() }

/** Creates a reopenable streaming part without retaining all bytes in memory. */
fun streamPart(
    fieldName: String,
    fileName: String,
    contentLength: Long,
    contentType: String = guessContentType(fileName),
    opener: () -> InputStream,
): VmodalFilePart = VmodalFilePart(fieldName, fileName, contentLength, contentType, opener)

/** @suppress */
fun guessContentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "json", "jsonl" -> "application/json"
    "txt" -> "text/plain"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "mp4" -> "video/mp4"
    else -> "application/octet-stream"
}

private fun VmodalRequest.hasBody(): Boolean = jsonBody != null || formFields.isNotEmpty() || files.isNotEmpty()

internal fun Map<String, Any?>.toQueryString(trimPrefix: Boolean = false): String {
    val pairs = flatMap { (key, value) ->
        when (value) {
            null -> emptyList()
            is Iterable<*> -> value.map { key to it }
            is Array<*> -> value.map { key to it }
            else -> listOf(key to value)
        }
    }.filter { it.second != null }
    if (pairs.isEmpty()) return ""
    val body = pairs.joinToString("&") { (key, value) -> key.urlEncode() + "=" + value.toString().urlEncode() }
    return if (trimPrefix) body else "?$body"
}

private fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8).replace("+", "%20")

internal fun strMultipartValue(name: String, value: String, maxLength: Int): String {
    if (value.isBlank()) throw ValidationFailed("$name must not be blank")
    if (value.length > maxLength) throw ValidationFailed("$name is too long")
    if (value.any { it == '\u0000' || it.isISOControl() }) throw ValidationFailed("$name contains control characters")
    return value
}

private fun String.strMultipartQuoted(): String = replace("\\", "\\\\").replace("\"", "\\\"")

internal fun VmodalRequest.validateMultipart() {
    formFields.keys.forEach { strMultipartValue("field name", it, 128) }
}

internal fun VmodalRequest.writeMultipart(out: OutputStream, boundary: String) {
    validateMultipart()
    fun text(value: String) = out.write(value.toByteArray(StandardCharsets.UTF_8))
    formFields.forEach { (key, value) ->
        val values = if (value is Iterable<*>) value.toList() else listOf(value)
        values.filterNotNull().forEach {
            text("--$boundary\r\nContent-Disposition: form-data; name=\"${key.strMultipartQuoted()}\"\r\n\r\n$it\r\n")
        }
    }
    files.forEach { part ->
        text("--$boundary\r\nContent-Disposition: form-data; name=\"${part.fieldName.strMultipartQuoted()}\"; filename=\"${part.fileName.strMultipartQuoted()}\"\r\n")
        text("Content-Type: ${part.contentType}\r\n\r\n")
        part.open().use { it.copyTo(out, 1024 * 1024) }
        text("\r\n")
    }
    text("--$boundary--\r\n")
}
