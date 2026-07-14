package com.vmodal.sdk

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

class VmodalFilePart(
    val fieldName: String,
    val fileName: String,
    val contentLength: Long,
    val contentType: String = "application/octet-stream",
    private val opener: () -> InputStream,
) {
    // A reopenable stream keeps multipart uploads retryable without retaining the file in heap.
    // Android callers can pass { contentResolver.openInputStream(uri)!! } as the opener.
    constructor(fieldName: String, fileName: String, bytes: ByteArray, contentType: String = "application/octet-stream") :
        this(fieldName, fileName, bytes.size.toLong(), contentType, { bytes.inputStream() })

    fun open(): InputStream = opener()
}

data class VmodalRequest(
    val method: String,
    val path: String,
    val queryParameters: Map<String, Any?> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val jsonBody: Any? = null,
    val formFields: Map<String, Any?> = emptyMap(),
    val files: List<VmodalFilePart> = emptyList(),
)

data class VmodalResponse(
    val statusCode: Int,
    val body: String = "",
    val headers: Map<String, List<String>> = emptyMap(),
    val bytes: ByteArray = body.toByteArray(StandardCharsets.UTF_8),
) {
    val json: Any? by lazy { if (body.isBlank()) null else runCatching { VmodalJson.parse(body) }.getOrNull() }

    @Suppress("UNCHECKED_CAST")
    fun jsonObject(): Map<String, Any?> = json as? Map<String, Any?> ?: emptyMap()
}

interface VmodalTransport {
    fun execute(request: VmodalRequest): VmodalResponse
}

class HttpUrlConnectionTransport(private val cfg: SdkConfig) : VmodalTransport {
    override fun execute(request: VmodalRequest): VmodalResponse {
        val base = if (request.path.startsWith("http://") || request.path.startsWith("https://")) "" else cfg.normalizedBaseUrl
        val url = URL(base + request.path + request.queryParameters.toQueryString())
        val boundary = "----vmodal-${System.currentTimeMillis()}"
        val conn = (url.openConnection() as HttpURLConnection).apply {
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
        val stream = if (status in 200..399) conn.inputStream else conn.errorStream
        // API responses are small JSON or requested image bytes. Upload request bodies are streamed above.
        val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
        val headers = conn.headerFields.entries.mapNotNull { (key, value) -> key?.let { it to value } }.toMap()
        return VmodalResponse(status, bytes.toString(StandardCharsets.UTF_8), headers, bytes)
    }
}

fun filePart(fieldName: String, file: File, contentType: String = guessContentType(file.name)): VmodalFilePart =
    VmodalFilePart(fieldName, file.name, file.length(), contentType) { file.inputStream() }

fun streamPart(
    fieldName: String,
    fileName: String,
    contentLength: Long,
    contentType: String = guessContentType(fileName),
    opener: () -> InputStream,
): VmodalFilePart = VmodalFilePart(fieldName, fileName, contentLength, contentType, opener)

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

private fun VmodalRequest.writeMultipart(out: java.io.OutputStream, boundary: String) {
    fun text(value: String) = out.write(value.toByteArray(StandardCharsets.UTF_8))
    formFields.forEach { (key, value) ->
        val values = if (value is Iterable<*>) value.toList() else listOf(value)
        values.filterNotNull().forEach {
            text("--$boundary\r\nContent-Disposition: form-data; name=\"$key\"\r\n\r\n$it\r\n")
        }
    }
    files.forEach { part ->
        text("--$boundary\r\nContent-Disposition: form-data; name=\"${part.fieldName}\"; filename=\"${part.fileName}\"\r\n")
        text("Content-Type: ${part.contentType}\r\n\r\n")
        part.open().use { it.copyTo(out, 1024 * 1024) }
        text("\r\n")
    }
    text("--$boundary--\r\n")
}
