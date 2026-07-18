package com.vmodal.sdk

/** Published SDK semantic version. */
const val VMODAL_SDK_VERSION = "1.0.0"

/**
 * Main SDK entry point and owner of resource clients.
 *
 * Blocking calls must run on a worker thread. Inject transports for tests or
 * custom integrations; the client does not own externally supplied transports.
 *
 * @property cfg validated client configuration
 * @property http low-level request facade
 * @property auth authentication operations
 * @property searches multimodal search operations
 * @property collections collection and upload operations
 * @property indexes index lifecycle operations
 * @property admin administrative reporting operations
 * @property gdrive disabled legacy Google Drive operations
 * @property sql disabled legacy SQL operations
 * @property images image retrieval operations
 * @property r2 object-storage signing operations
 */
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

    /** Builds a gateway client from explicit connection and identity fields. */
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

    /** Checks service availability. This call blocks. */
    fun health(): HealthResponse = auth.health()
    /** Returns whether authenticated access succeeds. This call blocks. */
    fun authCheck(userId: String = ""): Boolean = auth.authCheck(userId)

    /** Factories for environment and trusted-direct configuration. */
    companion object {
        /** Builds a client from environment configuration and optionally resolves identity. */
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
