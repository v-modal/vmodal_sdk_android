package com.vmodal.sdk

class VmodalHttp(
    val cfg: SdkConfig,
    val transport: VmodalTransport = HttpUrlConnectionTransport(cfg),
) {
    fun headers(forceToken: Boolean = false, requireUserId: Boolean = true): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val userId = cfg.normalizedUserId
        if (requireUserId && userId.isBlank()) throw AuthError("user_id is required")
        if (userId.isNotBlank()) out["X-User-Id"] = strHeaderValue("user_id", userId)
        if (cfg.normalizedTenantId.isNotBlank()) out["X-Tenant-Id"] = strHeaderValue("tenant_id", cfg.normalizedTenantId)
        if (cfg.normalizedEmail.isNotBlank()) out["X-User-Email"] = strHeaderValue("email", cfg.normalizedEmail)
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
    ): ByteArray = execute(method, path, json, params = params, headers = headers()).bytes

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
    ): VmodalResponse {
        strRequireSameOrigin(path, cfg.normalizedBaseUrl)
        val attempts = cfg.normalizedMaxRetries + 1
        var last: RuntimeException? = null
        repeat(attempts) { idx ->
            try {
                val res = transport.execute(VmodalRequest(method.uppercase(), path, params, headers, json, data, files))
                if (res.statusCode in RETRY_CODES && idx + 1 < attempts) {
                    retrySleep(idx)
                    return@repeat
                }
                raiseForStatus(res)
                return res
            } catch (exc: AuthError) {
                throw exc
            } catch (exc: ValidationFailed) {
                throw exc
            } catch (exc: ApiError) {
                last = exc
                if (exc.statusCode in RETRY_CODES && idx + 1 < attempts) {
                    retrySleep(idx)
                    return@repeat
                }
                throw exc
            } catch (exc: RuntimeException) {
                last = exc
                if (idx + 1 < attempts && exc::class.simpleName.orEmpty().contains("timeout", true)) {
                    retrySleep(idx)
                    return@repeat
                }
                throw ApiError("transport error", body = exc.message)
            }
        }
        throw last ?: ApiError("request failed")
    }

    private fun raiseForStatus(response: VmodalResponse) {
        if (response.statusCode in 200..299) return
        val body = response.json ?: response.body
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

    private fun retrySleep(idx: Int) = Thread.sleep(50L * (idx + 1))

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
