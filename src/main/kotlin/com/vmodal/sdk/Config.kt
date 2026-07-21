package com.vmodal.sdk

/** @suppress */
@JvmField val PUBLIC_GATEWAY_URL_HASH = RoutesGenerated.PUBLIC_GATEWAY_URL_HASH
/** @suppress */
@JvmField val PUBLIC_GATEWAY_URL = RoutesGenerated.public_gateway_url
/** @suppress */
@JvmField val DEV_GATEWAY_URL = RoutesGenerated.dev_gateway_url

/**
 * Immutable client configuration.
 *
 * Credentials may be supplied by [apiKeyProvider] so long-lived clients can
 * observe rotation without being rebuilt. Timeouts must be positive and retry
 * counts must not be negative.
 *
 * @property baseUrl service base URL
 * @property userId optional resolved user identifier
 * @property tenantId optional resolved tenant identifier
 * @property email optional resolved user email
 * @property token static API credential
 * @property timeoutMillis request timeout in milliseconds
 * @property mode gateway or trusted-direct mode
 * @property maxRetries maximum safe-read retries
 * @property apiKeyProvider optional rotating credential provider
 */
data class SdkConfig(
    val baseUrl: String = PUBLIC_GATEWAY_URL,
    val userId: String = "",
    val tenantId: String = "",
    val email: String = "",
    val token: String = "",
    val timeoutMillis: Int = 30_000,
    val mode: String = "gateway",
    val maxRetries: Int = 1,
    val apiKeyProvider: ApiKeyProvider? = null,
) {
    private var diagnosticConfig: SdkDiagnostics = SdkDiagnostics.disabled()

    init {
        if (timeoutMillis <= 0) throw ValidationFailed("timeout_millis must be positive")
        if (maxRetries < 0) throw ValidationFailed("max_retries must not be negative")
        if (mode.trim().lowercase().ifBlank { "gateway" } !in setOf("gateway", "direct")) {
            throw ValidationFailed("mode must be gateway or direct")
        }
    }

    /** Normalized connection mode. */
    val normalizedMode: String = mode.trim().lowercase().ifBlank { "gateway" }
    /** Normalized service base URL. */
    val normalizedBaseUrl: String = strGatewayBaseUrl(baseUrl, normalizedMode)
    /** Trimmed user identifier. */
    val normalizedUserId: String = userId.trim()
    /** Trimmed tenant identifier. */
    val normalizedTenantId: String = tenantId.trim()
    /** Trimmed email address. */
    val normalizedEmail: String = email.trim()
    /** Trimmed static credential. */
    val normalizedToken: String = token.trim()
    /** Validated safe-read retry count. */
    val normalizedMaxRetries: Int = maxRetries
    /** Sanitized network diagnostics configuration; disabled by default. */
    val diagnostics: SdkDiagnostics get() = diagnosticConfig

    /**
     * Returns an otherwise identical configuration with explicitly selected diagnostics.
     *
     * Apply this after the final generated [copy] call. Diagnostics are intentionally not part
     * of the frozen data-class constructor, so [copy] only copies the original configuration
     * fields.
     */
    fun withDiagnostics(value: SdkDiagnostics): SdkConfig = copy().also { it.diagnosticConfig = value }

    internal fun currentApiKey(): String {
        val key = apiKeyProvider?.current() ?: normalizedToken
        return if (key.isBlank()) "" else strApiKey(key)
    }

    /** Returns redacted configuration diagnostics. */
    override fun toString(): String = buildString {
        append("SdkConfig(baseUrlConfigured=").append(baseUrl.isNotBlank())
        append(", userIdConfigured=").append(userId.isNotBlank())
        append(", tenantIdConfigured=").append(tenantId.isNotBlank())
        append(", emailConfigured=").append(email.isNotBlank())
        append(", tokenConfigured=").append(token.isNotBlank())
        append(", timeoutMillis=").append(timeoutMillis)
        append(", mode=").append(normalizedMode)
        append(", maxRetries=").append(maxRetries)
        append(", apiKeyProviderConfigured=").append(apiKeyProvider != null)
        append(", diagnostics=").append(diagnostics)
        append(')')
    }

    /** Environment-based configuration factory. */
    companion object {
        /** Builds validated configuration from an environment map plus explicit overrides. */
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

/** @suppress */
fun strGatewayBaseUrl(baseUrl: String, mode: String = ""): String {
    val base = baseUrl.trim().trimEnd('/')
    if (base.isBlank() || mode.trim().lowercase() != "gateway") return base
    val suffix = RoutesGenerated.gateway_proxy_suffix
    return if (base.endsWith(suffix)) base else base + suffix
}

/** @suppress */
fun strUsersBaseUrl(baseUrl: String): String {
    val base = baseUrl.trim().trimEnd('/')
    val suffix = RoutesGenerated.gateway_proxy_suffix
    return if (base.endsWith(suffix)) base.removeSuffix(suffix) else base
}

/** @suppress */
internal fun strAbsoluteImageUrl(value: String, baseUrl: String): String {
    val raw = value.trim()
    if (raw.isBlank() || raw.matches(Regex("^[A-Za-z][A-Za-z0-9+.-]*:.*"))) return raw
    val base = baseUrl.trim().trimEnd('/')
    if (base.isBlank()) return raw
    if (raw.startsWith("//")) return base.substringBefore(':') + ":" + raw

    val path = raw.trimStart('/')
    val proxyPath = RoutesGenerated.gateway_proxy_suffix.trimStart('/')
    return if (path == proxyPath || path.startsWith("$proxyPath/")) {
        strUsersBaseUrl(base) + "/" + path
    } else {
        "$base/$path"
    }
}

/** @suppress */
internal fun mapAbsoluteImageUrl(raw: Map<String, Any?>, baseUrl: String): Map<String, Any?> {
    val value = raw["url_pre_signed"]?.toString().orEmpty()
    if (value.isBlank()) return raw
    return raw + ("url_pre_signed" to strAbsoluteImageUrl(value, baseUrl))
}

/** @suppress */
internal fun mapAbsoluteImageUrls(raw: Map<String, Any?>, baseUrl: String): Map<String, Any?> {
    val records = raw["records"] as? List<*> ?: return raw
    val values = records.map { value ->
        val row = (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
        if (row == null) value else mapAbsoluteImageUrl(row, baseUrl)
    }
    return raw + ("records" to values)
}
