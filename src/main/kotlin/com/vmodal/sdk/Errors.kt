package com.vmodal.sdk

import java.io.IOException

open class SdkError(
    message: String,
    val statusCode: Int = 0,
    val body: Any? = null,
    val details: Any? = null,
) : RuntimeException(message) {
    override fun toString(): String {
        val parts = mutableListOf(message ?: this::class.simpleName.orEmpty())
        if (statusCode != 0) parts += "status=$statusCode"
        return parts.joinToString(" | ")
    }
}

class AuthError(message: String, statusCode: Int = 401, body: Any? = null, details: Any? = null) :
    SdkError(message, statusCode, body, details)

class ApiError(message: String, statusCode: Int = 0, body: Any? = null, details: Any? = null) :
    SdkError(message, statusCode, body, details)

class ValidationFailed(message: String, statusCode: Int = 422, body: Any? = null, details: Any? = null) :
    SdkError(message, statusCode, body, details)

class FeatureDisabled(message: String) : SdkError(message)

class TransportError(cause: IOException) : SdkError("transport error") {
    init {
        initCause(cause)
    }
}

class ResponseTooLarge(
    val limitBytes: Long,
    val observedBytes: Long,
) : SdkError("response exceeds the configured limit")

class MalformedResponse(message: String = "malformed JSON response") : SdkError(message)
