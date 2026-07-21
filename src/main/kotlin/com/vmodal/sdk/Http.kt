package com.vmodal.sdk

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay

/**
 * Supported low-level request facade used by resource classes.
 *
 * Calls are synchronous. Safe reads may retry according to [SdkConfig];
 * mutations are never replayed automatically.
 *
 * @property cfg validated request configuration
 * @property transport transport used to execute requests
 */
class VmodalHttp(
    val cfg: SdkConfig,
    val transport: VmodalTransport = HttpUrlConnectionTransport(cfg),
) {
    /** Builds validated authentication and trusted-direct-mode headers. */
    fun headers(forceToken: Boolean = false, requireUserId: Boolean = true): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val userId = cfg.normalizedUserId
        if (cfg.normalizedMode == "direct") {
            if (requireUserId && userId.isBlank()) throw AuthError("user_id is required")
            if (userId.isNotBlank()) out["X-User-Id"] = strHeaderValue("user_id", userId)
            if (cfg.normalizedTenantId.isNotBlank()) out["X-Tenant-Id"] = strHeaderValue("tenant_id", cfg.normalizedTenantId)
            if (cfg.normalizedEmail.isNotBlank()) out["X-User-Email"] = strHeaderValue("email", cfg.normalizedEmail)
        }
        if (forceToken || cfg.normalizedMode != "direct") {
            val apiKey = cfg.currentApiKey()
            if (apiKey.isBlank()) throw AuthError("API key is required")
            out["Authorization"] = "Bearer $apiKey"
        }
        return out
    }

    /** Executes a structured request and returns a decoded object. */
    fun request(
        method: String,
        path: String,
        json: Any? = null,
        data: Map<String, Any?> = emptyMap(),
        files: List<VmodalFilePart> = emptyList(),
        params: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = execute(method, path, json, data, files, params, headers()).jsonObject()

    /** Executes a bounded in-memory binary request. */
    fun requestBytes(
        method: String,
        path: String,
        json: Any? = null,
        params: Map<String, Any?> = emptyMap(),
    ): ByteArray = execute(
        method,
        path,
        json,
        params = params,
        headers = headers(),
        responseMode = VmodalResponseMode.BYTES,
    ).bytes

    /** Executes a credential-authenticated user-lifecycle request. */
    fun requestUsers(
        method: String,
        path: String,
        json: Any? = null,
        params: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val headers = headers(forceToken = true, requireUserId = false)
        if (headers["Authorization"].isNullOrBlank()) throw AuthError("API key is required for users_api routes")
        val url = if (strIsAbsoluteHttpUrl(path)) path else strUsersBaseUrl(cfg.normalizedBaseUrl) + path
        return execute(
            method,
            url,
            json,
            params = params,
            headers = headers,
            transportKind = DiagnosticTransportKind.USERS_API,
        ).jsonObject()
    }

    /** Coroutine counterpart used by the additive coroutine facade. */
    internal suspend fun requestSuspend(
        method: String,
        path: String,
        json: Any? = null,
        data: Map<String, Any?> = emptyMap(),
        files: List<VmodalFilePart> = emptyList(),
        params: Map<String, Any?> = emptyMap(),
        fallbackDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): Map<String, Any?> = executeSuspend(
        method,
        path,
        json,
        data,
        files,
        params,
        headers(),
        fallbackDispatcher = fallbackDispatcher,
    ).jsonObject()

    /** Coroutine counterpart for bounded binary requests. */
    internal suspend fun requestBytesSuspend(
        method: String,
        path: String,
        json: Any? = null,
        params: Map<String, Any?> = emptyMap(),
        fallbackDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): ByteArray = executeSuspend(
        method,
        path,
        json,
        params = params,
        headers = headers(),
        responseMode = VmodalResponseMode.BYTES,
        fallbackDispatcher = fallbackDispatcher,
    ).bytes

    /** Coroutine counterpart for credential-authenticated user-lifecycle requests. */
    internal suspend fun requestUsersSuspend(
        method: String,
        path: String,
        json: Any? = null,
        params: Map<String, Any?> = emptyMap(),
        fallbackDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): Map<String, Any?> {
        val headers = headers(forceToken = true, requireUserId = false)
        if (headers["Authorization"].isNullOrBlank()) throw AuthError("API key is required for users_api routes")
        val url = if (strIsAbsoluteHttpUrl(path)) path else strUsersBaseUrl(cfg.normalizedBaseUrl) + path
        return executeSuspend(
            method,
            url,
            json,
            params = params,
            headers = headers,
            transportKind = DiagnosticTransportKind.USERS_API,
            fallbackDispatcher = fallbackDispatcher,
        ).jsonObject()
    }

    private fun execute(
        method: String,
        path: String,
        json: Any? = null,
        data: Map<String, Any?> = emptyMap(),
        files: List<VmodalFilePart> = emptyList(),
        params: Map<String, Any?> = emptyMap(),
        headers: Map<String, String>,
        responseMode: VmodalResponseMode = VmodalResponseMode.TEXT,
        transportKind: DiagnosticTransportKind = DiagnosticTransportKind.GATEWAY,
    ): VmodalResponse {
        val request = prepareRequest(method, path, json, data, files, params, headers, responseMode)
        val attempts = attemptCount(request.method)
        val diagnostics = cfg.diagnostics
        val correlationId = diagnostics.strCorrelationId()
        val metadata = if (diagnostics.enabled) {
            diagnosticRequestMetadataSafely(json, data, params, files, responseMode)
        } else {
            null
        }
        var last: IOException? = null
        repeat(attempts) { idx ->
            var terminal = false
            val item = metadata?.let {
                diagnostics.startAttempt(
                    correlationId,
                    transportKind,
                    request.method,
                    idx + 1,
                    if (transportKind == DiagnosticTransportKind.USERS_API) "users-api" else "gateway",
                    it,
                )
            }
            try {
                val res = transport.execute(request)
                if (shouldRetry(request.method, res.statusCode, idx, attempts)) {
                    diagnostics.failure(item, ApiError("api request failed", statusCode = res.statusCode))
                    terminal = true
                    retrySleep(idx)
                    return@repeat
                }
                val checked = responseOrThrow(res)
                if (responseMode == VmodalResponseMode.TEXT) checked.jsonObject()
                diagnostics.response(
                    item,
                    checked.statusCode,
                    strResponseContentType(checked.headers),
                    checked.bytes.size.toLong(),
                    checked.body,
                )
                terminal = true
                return checked
            } catch (exc: IOException) {
                if (!terminal) diagnostics.failure(item, exc)
                last = exc
                if (hasNextAttempt(idx, attempts)) {
                    retrySleep(idx)
                    return@repeat
                }
                throw TransportError(exc)
            } catch (exc: Throwable) {
                if (!terminal) diagnostics.failure(item, exc)
                throw exc
            }
        }
        throw TransportError(last ?: IOException("request failed"))
    }

    private suspend fun executeSuspend(
        method: String,
        path: String,
        json: Any? = null,
        data: Map<String, Any?> = emptyMap(),
        files: List<VmodalFilePart> = emptyList(),
        params: Map<String, Any?> = emptyMap(),
        headers: Map<String, String>,
        responseMode: VmodalResponseMode = VmodalResponseMode.TEXT,
        transportKind: DiagnosticTransportKind = DiagnosticTransportKind.GATEWAY,
        fallbackDispatcher: CoroutineDispatcher,
    ): VmodalResponse {
        val request = prepareRequest(method, path, json, data, files, params, headers, responseMode)
        val attempts = attemptCount(request.method)
        val diagnostics = cfg.diagnostics
        val correlationId = diagnostics.strCorrelationId()
        val metadata = if (diagnostics.enabled) {
            diagnosticRequestMetadataSafely(json, data, params, files, responseMode)
        } else {
            null
        }
        var last: IOException? = null
        repeat(attempts) { idx ->
            var terminal = false
            val item = metadata?.let {
                diagnostics.startAttempt(
                    correlationId,
                    transportKind,
                    request.method,
                    idx + 1,
                    if (transportKind == DiagnosticTransportKind.USERS_API) "users-api" else "gateway",
                    it,
                )
            }
            try {
                val res = transport.executeCancellable(request, fallbackDispatcher)
                if (shouldRetry(request.method, res.statusCode, idx, attempts)) {
                    diagnostics.failure(item, ApiError("api request failed", statusCode = res.statusCode))
                    terminal = true
                    delay(retryDelayMillis(idx))
                    return@repeat
                }
                val checked = responseOrThrow(res)
                if (responseMode == VmodalResponseMode.TEXT) checked.jsonObject()
                diagnostics.response(
                    item,
                    checked.statusCode,
                    strResponseContentType(checked.headers),
                    checked.bytes.size.toLong(),
                    checked.body,
                )
                terminal = true
                return checked
            } catch (exc: IOException) {
                if (!terminal) diagnostics.failure(item, exc)
                last = exc
                if (hasNextAttempt(idx, attempts)) {
                    delay(retryDelayMillis(idx))
                    return@repeat
                }
                throw TransportError(exc)
            } catch (exc: Throwable) {
                if (!terminal) diagnostics.failure(item, exc)
                throw exc
            }
        }
        throw TransportError(last ?: IOException("request failed"))
    }

    private fun prepareRequest(
        method: String,
        path: String,
        json: Any?,
        data: Map<String, Any?>,
        files: List<VmodalFilePart>,
        params: Map<String, Any?>,
        headers: Map<String, String>,
        responseMode: VmodalResponseMode,
    ): VmodalRequest {
        strRequireSameOrigin(path, cfg.normalizedBaseUrl)
        return VmodalRequest(method.uppercase(), path, params, headers, json, data, files).also {
            it.responseMode = responseMode
        }
    }

    private fun attemptCount(method: String): Int =
        if (isRetryableMethod(method)) cfg.normalizedMaxRetries + 1 else 1

    private fun shouldRetry(method: String, statusCode: Int, idx: Int, attempts: Int): Boolean =
        isRetryableMethod(method) && statusCode in RETRY_CODES && hasNextAttempt(idx, attempts)

    private fun responseOrThrow(response: VmodalResponse): VmodalResponse {
        if (response.statusCode in 200..299) return response
        val contentType = response.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }
            ?.value?.joinToString(";").orEmpty()
        val looksJson = "json" in contentType.lowercase() || response.body.trimStart().startsWith('{') ||
            response.body.trimStart().startsWith('[')
        val body = if (response.body.isEmpty()) null else if (looksJson) response.json else response.body
        when (response.statusCode) {
            401 -> throw AuthError("authentication failed", statusCode = 401, body = body)
            422 -> throw ValidationFailed(
                "validation failed",
                statusCode = 422,
                body = body,
                details = (body as? Map<*, *>)?.get("detail") ?: body,
            )
            else -> throw ApiError("api request failed", statusCode = response.statusCode, body = body)
        }
    }

    private fun retrySleep(idx: Int) {
        try {
            Thread.sleep(retryDelayMillis(idx))
        } catch (exc: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ApiError("request interrupted").also { it.initCause(exc) }
        }
    }

    private companion object {
        val RETRY_CODES = setOf(500, 502, 503, 504)

        fun isRetryableMethod(method: String): Boolean = method == "GET" || method == "HEAD"

        fun hasNextAttempt(idx: Int, attempts: Int): Boolean = idx + 1 < attempts

        fun retryDelayMillis(idx: Int): Long = 50L * (idx + 1)

        fun strHeaderValue(name: String, value: String): String {
            if (value.length > 4_096 || value.any { it.isISOControl() }) {
                throw ValidationFailed("$name contains invalid header characters")
            }
            return value
        }
    }
}
