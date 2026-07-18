package com.vmodal.sdk

import java.util.concurrent.atomic.AtomicReference

/** Supplies the current credential at request time. */
fun interface ApiKeyProvider {
    /** Returns the current credential or throws [AuthError] when unavailable. */
    fun current(): String
}

/** Thread-safe rotatable credential provider whose value is redacted in logs. */
class MutableApiKeyProvider(initialKey: String) : ApiKeyProvider, AutoCloseable {
    private val key = AtomicReference<String?>(strApiKey(initialKey))

    /** Replaces the current credential after validation. */
    fun rotate(newKey: String) {
        key.set(strApiKey(newKey))
    }

    /** Removes the credential so subsequent access fails with [AuthError]. */
    fun clear() {
        key.set(null)
    }

    /** Clears the credential when the provider is closed. */
    override fun close() = clear()

    override fun current(): String = key.get() ?: throw AuthError("API key is unavailable")

    /** Returns a representation that never reveals the credential. */
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
