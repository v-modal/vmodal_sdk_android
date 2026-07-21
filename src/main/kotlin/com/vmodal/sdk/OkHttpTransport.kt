package com.vmodal.sdk

import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink

/** Built-in blocking and cancellable transport backed by one OkHttp request encoder. */
class OkHttpTransport private constructor(
    private val cfg: SdkConfig,
    private val jsonLimitBytes: Long,
    private val errorLimitBytes: Long,
    private val binaryLimitBytes: Long,
    private val client: OkHttpClient,
) : CancellableVmodalTransport {
    internal constructor(cfg: SdkConfig, client: OkHttpClient) : this(
        cfg,
        JSON_RESPONSE_LIMIT_BYTES,
        ERROR_RESPONSE_LIMIT_BYTES,
        BINARY_RESPONSE_LIMIT_BYTES,
        client,
    )

    /** Creates a transport with the SDK response limits. */
    constructor(cfg: SdkConfig) : this(
        cfg,
        JSON_RESPONSE_LIMIT_BYTES,
        ERROR_RESPONSE_LIMIT_BYTES,
        BINARY_RESPONSE_LIMIT_BYTES,
    )

    /** Creates a transport with explicit structured, error, and binary response limits. */
    constructor(
        cfg: SdkConfig,
        jsonLimitBytes: Long,
        errorLimitBytes: Long,
        binaryLimitBytes: Long,
    ) : this(
        cfg,
        jsonLimitBytes,
        errorLimitBytes,
        binaryLimitBytes,
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(cfg.timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .connectTimeout(cfg.timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(cfg.timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(cfg.timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .build(),
    )

    init {
        listOf(jsonLimitBytes, errorLimitBytes, binaryLimitBytes).forEach {
            if (it <= 0 || it > Int.MAX_VALUE) throw ValidationFailed("response limit is invalid")
        }
    }

    override fun execute(request: VmodalRequest): VmodalResponse =
        client.newCall(okRequest(request)).execute().use { response -> response.toVmodalResponse(request) }

    override fun executeAsync(request: VmodalRequest, callback: VmodalTransportCallback): VmodalCancelHandle {
        val done = AtomicBoolean(false)
        val call = client.newCall(okRequest(request))
        fun fail(error: Throwable) {
            if (done.compareAndSet(false, true)) callback.onFailure(error)
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: java.io.IOException) {
                fail(if (call.isCanceled()) CancellationException("HTTP request cancelled") else error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use { it.toVmodalResponse(request) }
                    if (done.compareAndSet(false, true)) callback.onSuccess(result)
                } catch (error: Throwable) {
                    fail(error)
                }
            }
        })
        return VmodalCancelHandle {
            call.cancel()
            fail(CancellationException("HTTP request cancelled"))
        }
    }

    private fun okRequest(request: VmodalRequest): Request {
        if (request.files.isNotEmpty()) request.validateMultipart()
        val base = if (strIsAbsoluteHttpUrl(request.path)) "" else cfg.normalizedBaseUrl
        val url = validatedHttpUrl(base + request.path + request.queryParameters.toQueryString()).toString().toHttpUrl()
        val method = request.method.uppercase()
        val boundary = "----vmodal-${UUID.randomUUID()}"
        val body = request.okRequestBody(method, boundary)
        return Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply { request.headers.forEach { (key, value) -> header(key, value) } }
            .apply {
                when {
                    request.files.isNotEmpty() -> header("Content-Type", "multipart/form-data; boundary=$boundary")
                    request.formFields.isNotEmpty() -> header("Content-Type", "application/x-www-form-urlencoded")
                    request.jsonBody != null -> header("Content-Type", "application/json")
                }
            }
            .method(method, body)
            .build()
    }

    private fun VmodalRequest.okRequestBody(method: String, boundary: String): RequestBody? {
        if (method in setOf("GET", "HEAD")) return null
        return when {
            files.isNotEmpty() -> MultipartRequestBody(this, boundary)
            formFields.isNotEmpty() -> formFields.toQueryString(true)
                .toRequestBody("application/x-www-form-urlencoded".toMediaType())
            jsonBody != null -> VmodalJson.stringify(jsonBody).toRequestBody("application/json".toMediaType())
            method in setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT") -> ByteArray(0).toRequestBody(null)
            else -> null
        }
    }

    private fun Response.toVmodalResponse(request: VmodalRequest): VmodalResponse {
        val success = code in 200..299
        val limit = when {
            !success -> errorLimitBytes
            request.responseMode == VmodalResponseMode.BYTES -> binaryLimitBytes
            else -> jsonLimitBytes
        }
        val declared = responseContentLength(header("Content-Length"))
        val bytes = body?.byteStream()?.use { it.bytesBounded(declared, limit) } ?: ByteArray(0)
        val text = if (success && request.responseMode == VmodalResponseMode.BYTES) {
            ""
        } else {
            bytes.toString(StandardCharsets.UTF_8)
        }
        return VmodalResponse(code, text, headers.toMultimap(), bytes)
    }
}

private class MultipartRequestBody(
    private val request: VmodalRequest,
    private val boundary: String,
) : RequestBody() {
    override fun contentType(): MediaType = "multipart/form-data; boundary=$boundary".toMediaType()

    override fun writeTo(sink: BufferedSink) {
        request.writeMultipart(sink.outputStream(), boundary)
    }
}
