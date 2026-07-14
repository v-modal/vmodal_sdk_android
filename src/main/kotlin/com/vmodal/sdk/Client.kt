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
        baseUrl: String = "",
        userId: String = "",
        tenantId: String = "",
        email: String = "",
        token: String = "",
        timeoutMillis: Int = 30_000,
        mode: String = "direct",
        maxRetries: Int = 1,
        transport: VmodalTransport? = null,
        signedUploads: SignedUploadTransport? = null,
        apiKeyProvider: ApiKeyProvider? = null,
    ) : this(
        SdkConfig(baseUrl, userId, tenantId, email, token, timeoutMillis, mode, maxRetries, apiKeyProvider),
        transport ?: HttpUrlConnectionTransport(
            SdkConfig(baseUrl, userId, tenantId, email, token, timeoutMillis, mode, maxRetries, apiKeyProvider)
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
            // Gateway requests need the API-key owner fields for parity with Python SdkConfig.from_env.
            // Resolve them once via users_api; no identity values are accepted from the app blindly.
            val me = client.auth.me()
            val resolved = cfg.copy(userId = me.userId.orEmpty(), tenantId = me.tenantId.orEmpty(), email = me.email.orEmpty())
            if (resolved.normalizedUserId.isBlank()) throw AuthError("auth/me returned no user_id")
            return Client(resolved, transport ?: HttpUrlConnectionTransport(resolved), uploads)
        }
    }
}
