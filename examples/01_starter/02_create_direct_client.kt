package com.vmodal.sdk.examples

import com.vmodal.sdk.Client

fun createDirectClient(apiUrl: String, userId: String): Client = Client.unsafeDirect(
    baseUrl = apiUrl,
    userId = userId,
    timeoutMillis = 30_000,
    maxRetries = 2,
)
