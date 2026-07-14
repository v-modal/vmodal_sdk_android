package com.vmodal.sdk

object VmodalJson {
    fun stringify(value: Any?): String = when (value) {
        null -> "null"
        is String -> value.quoteJson()
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (key, entryValue) ->
            key.toString().quoteJson() + ":" + stringify(entryValue)
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { stringify(it) }
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { stringify(it) }
        else -> value.toString().quoteJson()
    }

    fun parse(text: String): Any? = Parser(text).parseValue()

    private fun String.quoteJson(): String = buildString {
        append('"')
        for (ch in this@quoteJson) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun parseValue(): Any? {
            skipWhitespace()
            if (index >= text.length) return null
            return when (text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> consume("true").let { true }
                'f' -> consume("false").let { false }
                'n' -> consume("null").let { null }
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val out = linkedMapOf<String, Any?>()
            skipWhitespace()
            if (peek('}')) return out.also { index++ }
            while (true) {
                val key = parseString()
                expect(':')
                out[key] = parseValue()
                skipWhitespace()
                if (peek('}')) return out.also { index++ }
                expect(',')
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val out = mutableListOf<Any?>()
            skipWhitespace()
            if (peek(']')) return out.also { index++ }
            while (true) {
                out += parseValue()
                skipWhitespace()
                if (peek(']')) return out.also { index++ }
                expect(',')
            }
        }

        private fun parseString(): String {
            expect('"')
            return buildString {
                while (index < text.length) {
                    when (val ch = text[index++]) {
                        '"' -> return@buildString
                        '\\' -> append(parseEscaped())
                        else -> append(ch)
                    }
                }
            }
        }

        private fun parseEscaped(): Char {
            val escaped = text[index++]
            return when (escaped) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> text.substring(index, index + 4).toInt(16).toChar().also { index += 4 }
                else -> escaped
            }
        }

        private fun parseNumber(): Number {
            val start = index
            while (index < text.length && text[index] !in listOf(',', '}', ']', ' ', '\n', '\r', '\t')) index++
            val raw = text.substring(start, index)
            return if (raw.contains('.') || raw.contains('e', ignoreCase = true)) raw.toDouble() else raw.toLong()
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        private fun expect(ch: Char) {
            skipWhitespace()
            require(index < text.length && text[index] == ch) { "Expected '$ch' at $index in $text" }
            index++
        }

        private fun consume(token: String) {
            require(text.startsWith(token, index)) { "Expected '$token' at $index in $text" }
            index += token.length
        }

        private fun peek(ch: Char): Boolean {
            skipWhitespace()
            return index < text.length && text[index] == ch
        }
    }
}
