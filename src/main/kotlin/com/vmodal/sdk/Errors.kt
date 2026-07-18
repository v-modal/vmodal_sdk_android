package com.vmodal.sdk

import java.io.IOException

/**
 * Base SDK failure with optional status and structured diagnostic details.
 *
 * @property statusCode HTTP-like status code, or zero when unavailable
 * @property body decoded or raw response body
 * @property details supplemental structured diagnostics
 */
open class SdkError(
    message: String,
    val statusCode: Int = 0,
    val body: Any? = null,
    val details: Any? = null,
) : RuntimeException(message) {
    /** Returns a concise status-aware failure description. */
    override fun toString(): String {
        val parts = mutableListOf(message ?: this::class.simpleName.orEmpty())
        if (statusCode != 0) parts += "status=$statusCode"
        return parts.joinToString(" | ")
    }
}

/** Authentication failed; callers should renew or replace credentials. */
class AuthError(message: String, statusCode: Int = 401, body: Any? = null, details: Any? = null) :
    SdkError(message, statusCode, body, details)

/** A service request failed while preserving status and response details. */
class ApiError(message: String, statusCode: Int = 0, body: Any? = null, details: Any? = null) :
    SdkError(message, statusCode, body, details)

/** Input validation failed; correct the request before retrying. */
class ValidationFailed(message: String, statusCode: Int = 422, body: Any? = null, details: Any? = null) :
    SdkError(message, statusCode, body, details)

/** A compatibility entry point is unavailable and should not be retried. */
class FeatureDisabled(message: String) : SdkError(message)

/** Connectivity failed before a usable response was received. */
class TransportError(cause: IOException) : SdkError("transport error") {
    init {
        initCause(cause)
    }
}

/**
 * A bounded response exceeded [limitBytes].
 *
 * @property limitBytes configured byte limit
 * @property observedBytes observed or declared response size
 */
class ResponseTooLarge(
    val limitBytes: Long,
    val observedBytes: Long,
) : SdkError("response exceeds the configured limit")

/** Structured response content could not be decoded. */
class MalformedResponse(message: String = "malformed JSON response") : SdkError(message)
