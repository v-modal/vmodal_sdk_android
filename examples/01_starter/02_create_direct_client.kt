package com.vmodal.sdk.examples

import com.vmodal.sdk.Client

fun createDirectClient(apiUrl: String, userId: String): Client = Client(
    baseUrl = apiUrl,
    userId = userId,
    mode = "direct",
    timeoutMillis = 30_000,
    maxRetries = 2,
)
