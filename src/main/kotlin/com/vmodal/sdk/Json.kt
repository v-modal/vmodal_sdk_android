package com.vmodal.sdk

import com.squareup.moshi.Moshi
import java.io.IOException

internal const val MAX_JSON_BYTES = 8L * 1024 * 1024

object VmodalJson {
    private val adapter = Moshi.Builder().build().adapter(Any::class.java).nullSafe()

    fun stringify(value: Any?): String = try {
        adapter.toJson(jsonValue(value))
    } catch (exc: ValidationFailed) {
        throw exc
    } catch (exc: RuntimeException) {
        throw ValidationFailed("value cannot be encoded as JSON")
    }

    fun parse(text: String): Any? {
        val size = jsonUtf8Size(text, MAX_JSON_BYTES)
        if (size > MAX_JSON_BYTES) throw ResponseTooLarge(MAX_JSON_BYTES, size)
        jsonValidateLexical(text)
        return try {
            adapter.fromJson(text)
        } catch (exc: IOException) {
            throw MalformedResponse()
        } catch (exc: RuntimeException) {
            throw MalformedResponse()
        }
    }

    private fun jsonValue(value: Any?): Any? = when (value) {
        null, is String, is Boolean -> value
        is Number -> value.also {
            val number = it.toDouble()
            if (!number.isFinite()) throw ValidationFailed("non-finite JSON numbers are not supported")
        }
        is Map<*, *> -> value.entries.associate { it.key.toString() to jsonValue(it.value) }
        is Iterable<*> -> value.map(::jsonValue)
        is Array<*> -> value.map(::jsonValue)
        else -> value.toString()
    }
}

private fun jsonValidateLexical(text: String) {
    var inString = false
    var escaped = false
    var depth = 0
    text.forEach { ch ->
        if (inString) {
            when {
                escaped -> escaped = false
                ch == '\\' -> escaped = true
                ch == '"' -> inString = false
                ch.code < 0x20 -> throw MalformedResponse()
            }
        } else {
            when (ch) {
                '"' -> inString = true
                '{', '[' -> {
                    depth++
                    if (depth > 256) throw MalformedResponse()
                }
                '}', ']' -> depth--
                else -> if (ch.code < 0x20 && ch !in listOf(' ', '\t', '\n', '\r')) throw MalformedResponse()
            }
        }
    }
}

internal fun jsonUtf8Size(text: String, stopAfter: Long = Long.MAX_VALUE): Long {
    var size = 0L
    var idx = 0
    while (idx < text.length && size <= stopAfter) {
        val ch = text[idx]
        size += when {
            ch.code <= 0x7f -> 1
            ch.code <= 0x7ff -> 2
            ch.isHighSurrogate() && idx + 1 < text.length && text[idx + 1].isLowSurrogate() -> {
                idx++
                4
            }
            else -> 3
        }
        idx++
    }
    return size
}
