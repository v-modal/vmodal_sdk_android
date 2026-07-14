package com.vmodal.sdk

import java.util.concurrent.atomic.AtomicReference

fun interface ApiKeyProvider {
    fun current(): String
}

class MutableApiKeyProvider(initialKey: String) : ApiKeyProvider, AutoCloseable {
    private val key = AtomicReference<String?>(strApiKey(initialKey))

    fun rotate(newKey: String) {
        key.set(strApiKey(newKey))
    }

    fun clear() {
        key.set(null)
    }

    override fun close() = clear()

    override fun current(): String = key.get() ?: throw AuthError("API key is unavailable")

    override fun toString(): String = "MutableApiKeyProvider([REDACTED])"
}

internal fun strApiKey(value: String): String {
    val key = value.trim()
    if (key.isBlank()) throw ValidationFailed("API key must not be blank")
    if (key.length > MAX_API_KEY_LENGTH) throw ValidationFailed("API key is too long")
    if (key.any { it.isISOControl() }) throw ValidationFailed("API key contains invalid characters")
    return key
}

private const val MAX_API_KEY_LENGTH = 8_192
