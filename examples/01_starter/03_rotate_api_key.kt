package com.vmodal.sdk.examples

import com.vmodal.sdk.Client
import com.vmodal.sdk.MutableApiKeyProvider
import com.vmodal.sdk.PUBLIC_GATEWAY_URL
import com.vmodal.sdk.SdkConfig

interface ApiKeyStore {
    fun load(): String?
    fun save(apiKey: String)
}

interface ApiKeyBackend {
    fun fetchLatest(): String
}

data class VmodalRuntime(
    val client: Client,
    val apiKeys: MutableApiKeyProvider,
)

// Call from Dispatchers.IO during application startup, then retain at application scope.
fun createRotatingClient(store: ApiKeyStore): VmodalRuntime {
    val apiKeys = MutableApiKeyProvider(requireNotNull(store.load()) { "No cached API key" })
    val bootstrap = Client(
        SdkConfig(
            baseUrl = PUBLIC_GATEWAY_URL,
            userId = "",
            mode = "gateway",
            apiKeyProvider = apiKeys,
        )
    )
    val me = bootstrap.auth.me()
    val client = Client(
        bootstrap.cfg.copy(
            userId = requireNotNull(me.userId),
            tenantId = me.tenantId.orEmpty(),
            email = me.email.orEmpty(),
        )
    )
    return VmodalRuntime(client, apiKeys)
}

// The host app owns backend access and persistence; the SDK only swaps the value.
fun refreshApiKey(runtime: VmodalRuntime, store: ApiKeyStore, backend: ApiKeyBackend) {
    val freshKey = backend.fetchLatest().trim()
    require(freshKey.isNotEmpty()) { "Backend returned no API key" }
    store.save(freshKey)
    runtime.apiKeys.rotate(freshKey)
}

// Also delete the app-owned persisted key before dropping the signed-in session.
fun clearApiKey(runtime: VmodalRuntime) {
    runtime.apiKeys.clear()
}
