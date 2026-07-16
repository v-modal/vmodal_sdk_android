package com.vmodal.sdk.examples

import com.vmodal.sdk.Client
import com.vmodal.sdk.PUBLIC_GATEWAY_URL

// Call from a worker thread: auth.me() performs a network request.
fun createGatewayClient(apiToken: String): Client {
    val bootstrap = Client(
        baseUrl = PUBLIC_GATEWAY_URL,
        token = apiToken,
    )
    val me = bootstrap.auth.me()
    return Client(
        bootstrap.cfg.copy(
            userId = requireNotNull(me.userId),
            tenantId = me.tenantId.orEmpty(),
            email = me.email.orEmpty(),
        )
    )
}
