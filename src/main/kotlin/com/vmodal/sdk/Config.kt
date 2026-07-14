package com.vmodal.sdk

const val PUBLIC_GATEWAY_URL = "https://searchapi-test.v-modal.com"
const val DEV_GATEWAY_URL = "http://127.0.0.1:3099"

data class SdkConfig(
    val baseUrl: String,
    val userId: String,
    val tenantId: String = "",
    val email: String = "",
    val token: String = "",
    val timeoutMillis: Int = 30_000,
    val mode: String = "direct",
    val maxRetries: Int = 1,
    val apiKeyProvider: ApiKeyProvider? = null,
) {
    val normalizedMode: String = mode.trim().lowercase().ifBlank { "direct" }
    val normalizedBaseUrl: String = strGatewayBaseUrl(baseUrl, normalizedMode)
    val normalizedUserId: String = userId.trim()
    val normalizedTenantId: String = tenantId.trim()
    val normalizedEmail: String = email.trim()
    val normalizedToken: String = token.trim()
    val normalizedMaxRetries: Int = maxOf(0, maxRetries)

    internal fun currentApiKey(): String {
        val key = apiKeyProvider?.current() ?: normalizedToken
        return if (key.isBlank()) "" else strApiKey(key)
    }

    override fun toString(): String = buildString {
        append("SdkConfig(baseUrl=").append(baseUrl)
        append(", userId=").append(userId)
        append(", tenantId=").append(tenantId)
        append(", email=").append(email)
        append(", token=[REDACTED]")
        append(", timeoutMillis=").append(timeoutMillis)
        append(", mode=").append(mode)
        append(", maxRetries=").append(maxRetries)
        append(", apiKeyProvider=").append(if (apiKeyProvider == null) "null" else "[configured]")
        append(')')
    }

    companion object {
        fun fromEnv(
            env: Map<String, String> = System.getenv(),
            baseUrl: String? = null,
            userId: String? = null,
            tenantId: String? = null,
            email: String? = null,
            token: String? = null,
            timeoutMillis: Int? = null,
            mode: String? = null,
            maxRetries: Int? = null,
        ): SdkConfig {
            val envName = env["VMODAL_ENV"].orEmpty().trim().lowercase().ifBlank { "prd" }
            if (envName !in listOf("dev", "prd")) throw ValidationFailed("VMODAL_ENV must be dev or prd")
            val envUrl = if (envName == "dev") DEV_GATEWAY_URL else PUBLIC_GATEWAY_URL
            val rawBase = baseUrl ?: env["VMODAL_BASE_URL"].orEmpty().ifBlank {
                env["TEST_CLIENT_SERVER_API_URL"].orEmpty().ifBlank { envUrl }
            }
            val rawToken = token ?: env["VMODAL_API_KEY"].orEmpty().ifBlank {
                env["VMODAL_API_TOKEN"].orEmpty().ifBlank {
                    env["TEST_CLIENT_CLERK_USER_API_TOKEN"].orEmpty().ifBlank { env["TEST_CLIENT_USER_TOKEN"].orEmpty() }
                }
            }
            val rawMode = mode ?: "gateway"
            val timeout = timeoutMillis ?: env["VMODAL_TIMEOUT"].orEmpty().toDoubleOrNull()?.times(1000)?.toInt() ?: 30_000
            if (rawToken.isBlank()) throw ValidationFailed("VMODAL_API_KEY is required")
            return SdkConfig(
                baseUrl = rawBase,
                userId = userId ?: env["VMODAL_USER_ID"].orEmpty(),
                tenantId = tenantId ?: env["VMODAL_TENANT_ID"].orEmpty(),
                email = email ?: env["VMODAL_USER_EMAIL"].orEmpty(),
                token = rawToken,
                timeoutMillis = timeout,
                mode = rawMode,
                maxRetries = maxRetries ?: env["VMODAL_MAX_RETRIES"].orEmpty().toIntOrNull() ?: 1,
            )
        }
    }
}

fun strGatewayBaseUrl(baseUrl: String, mode: String = ""): String {
    val base = baseUrl.trim().trimEnd('/')
    if (base.isBlank() || mode.trim().lowercase() != "gateway") return base
    val suffix = "/api/v1/proxy/search_api"
    return if (base.endsWith(suffix)) base else base + suffix
}

fun strUsersBaseUrl(baseUrl: String): String {
    val base = baseUrl.trim().trimEnd('/')
    val suffix = "/api/v1/proxy/search_api"
    return if (base.endsWith(suffix)) base.removeSuffix(suffix) else base
}
