package com.vmodal.sdk

import java.io.IOException

class VmodalHttp(
    val cfg: SdkConfig,
    val transport: VmodalTransport = HttpUrlConnectionTransport(cfg),
) {
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

    fun request(
        method: String,
        path: String,
        json: Any? = null,
        data: Map<String, Any?> = emptyMap(),
        files: List<VmodalFilePart> = emptyList(),
        params: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = execute(method, path, json, data, files, params, headers()).jsonObject()

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

    fun requestUsers(
        method: String,
        path: String,
        json: Any? = null,
        params: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val headers = headers(forceToken = true, requireUserId = false)
        if (headers["Authorization"].isNullOrBlank()) throw AuthError("API key is required for users_api routes")
        val url = if (strIsAbsoluteHttpUrl(path)) path else strUsersBaseUrl(cfg.normalizedBaseUrl) + path
        return execute(method, url, json, params = params, headers = headers).jsonObject()
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
    ): VmodalResponse {
        strRequireSameOrigin(path, cfg.normalizedBaseUrl)
        val normalizedMethod = method.uppercase()
        val canAutoRetry = normalizedMethod == "GET" || normalizedMethod == "HEAD"
        val attempts = cfg.normalizedMaxRetries + 1
        val request = VmodalRequest(normalizedMethod, path, params, headers, json, data, files).also {
            it.responseMode = responseMode
        }
        var last: IOException? = null
        repeat(attempts) { idx ->
            try {
                val res = transport.execute(request)
                if (canAutoRetry && res.statusCode in RETRY_CODES && idx + 1 < attempts) {
                    retrySleep(idx)
                    return@repeat
                }
                raiseForStatus(res)
                return res
            } catch (exc: IOException) {
                last = exc
                if (canAutoRetry && idx + 1 < attempts) {
                    retrySleep(idx)
                    return@repeat
                }
                throw TransportError(exc)
            }
        }
        throw TransportError(last ?: IOException("request failed"))
    }

    private fun raiseForStatus(response: VmodalResponse) {
        if (response.statusCode in 200..299) return
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
            Thread.sleep(50L * (idx + 1))
        } catch (exc: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ApiError("request interrupted").also { it.initCause(exc) }
        }
    }

    private companion object {
        val RETRY_CODES = setOf(500, 502, 503, 504)

        fun strHeaderValue(name: String, value: String): String {
            if (value.length > 4_096 || value.any { it.isISOControl() }) {
                throw ValidationFailed("$name contains invalid header characters")
            }
            return value
        }
    }
}
