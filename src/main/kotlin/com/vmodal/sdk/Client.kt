package com.vmodal.sdk

const val VMODAL_SDK_VERSION = "1.0.0"

class Client(
    val cfg: SdkConfig,
    transport: VmodalTransport = HttpUrlConnectionTransport(cfg),
    signedUploads: SignedUploadTransport = OkHttpSignedUploadTransport(cfg.timeoutMillis.toLong()),
) {
    val http = VmodalHttp(cfg, transport)
    val auth = AuthResource(http)
    val searches = SearchesResource(http)
    val collections = CollectionsResource(http, signedUploads)
    val indexes = IndexesResource(http)
    val admin = AdminResource(http)
    val gdrive = GDriveResource()
    val sql = SqlResource()
    val images = ImagesResource(http)
    val r2 = R2Resource(http)

    constructor(
        baseUrl: String = PUBLIC_GATEWAY_URL,
        userId: String = "",
        tenantId: String = "",
        email: String = "",
        token: String = "",
        timeoutMillis: Int = 30_000,
        maxRetries: Int = 1,
        transport: VmodalTransport? = null,
        signedUploads: SignedUploadTransport? = null,
        apiKeyProvider: ApiKeyProvider? = null,
    ) : this(
        SdkConfig(baseUrl, userId, tenantId, email, token, timeoutMillis, "gateway", maxRetries, apiKeyProvider),
        transport ?: HttpUrlConnectionTransport(
            SdkConfig(baseUrl, userId, tenantId, email, token, timeoutMillis, "gateway", maxRetries, apiKeyProvider)
        ),
        signedUploads ?: OkHttpSignedUploadTransport(timeoutMillis.toLong()),
    )

    fun health(): HealthResponse = auth.health()
    fun authCheck(userId: String = ""): Boolean = auth.authCheck(userId)

    companion object {
        fun fromEnv(
            env: Map<String, String> = System.getenv(),
            transport: VmodalTransport? = null,
            signedUploads: SignedUploadTransport? = null,
            resolveIdentity: Boolean = true,
        ): Client {
            val cfg = SdkConfig.fromEnv(env)
            val net = transport ?: HttpUrlConnectionTransport(cfg)
            val uploads = signedUploads ?: OkHttpSignedUploadTransport(cfg.timeoutMillis.toLong())
            val client = Client(cfg, net, uploads)
            if (!resolveIdentity || cfg.normalizedUserId.isNotBlank()) return client
            // Resolve profile fields for client-side identity/reporting. Gateway requests still
            // authenticate only with the bearer token and never forward these values as identity.
            val me = client.auth.me()
            val resolved = cfg.copy(userId = me.userId.orEmpty(), tenantId = me.tenantId.orEmpty(), email = me.email.orEmpty())
            if (resolved.normalizedUserId.isBlank()) throw AuthError("auth/me returned no user_id")
            return Client(resolved, transport ?: HttpUrlConnectionTransport(resolved), uploads)
        }

        /** Trusted-network escape hatch. Never use caller identity as an Internet trust boundary. */
        fun unsafeDirect(
            baseUrl: String,
            userId: String,
            tenantId: String = "",
            email: String = "",
            timeoutMillis: Int = 30_000,
            maxRetries: Int = 1,
            transport: VmodalTransport? = null,
            signedUploads: SignedUploadTransport? = null,
        ): Client {
            val cfg = SdkConfig(baseUrl, userId, tenantId, email, timeoutMillis = timeoutMillis, mode = "direct", maxRetries = maxRetries)
            return Client(
                cfg,
                transport ?: HttpUrlConnectionTransport(cfg),
                signedUploads ?: OkHttpSignedUploadTransport(timeoutMillis.toLong()),
            )
        }
    }
}
