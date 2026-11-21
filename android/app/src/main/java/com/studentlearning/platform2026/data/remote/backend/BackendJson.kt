package com.studentlearning.platform2026.data.remote.backend

import java.net.URI
import java.net.URLEncoder

/**
 * Pure-Kotlin RFC 8259 JSON parser & serializer + Backend API URL helpers.
 *
 * Intentionally avoids `org.json.JSONObject` and `android.*` APIs so data sources remain
 * runnable directly in pure JVM JUnit tests without Android framework mocks.
 */
internal object BackendJson {

    fun parseObject(raw: String): Map<String, Any?> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyMap()
        val parsed = Parser(trimmed).parseRoot()
        @Suppress("UNCHECKED_CAST")
        return parsed as? Map<String, Any?>
            ?: throw IllegalArgumentException("Expected JSON object root.")
    }

    fun writeObject(map: Map<String, Any?>): String {
        val sb = StringBuilder()
        appendValue(sb, map)
        return sb.toString()
    }

    fun normalizeApiRootUrl(
        rawBaseUrl: String?,
        allowInsecureHttpForTesting: Boolean = false
    ): String? {
        val trimmed = rawBaseUrl?.trim()?.trimEnd('/') ?: return null
        if (trimmed.isEmpty()) return null

        val uri = try {
            URI(trimmed)
        } catch (_: Throwable) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val isHttps = scheme == "https"
        val isAllowedLoopbackHttp = allowInsecureHttpForTesting &&
            scheme == "http" &&
            (host == "127.0.0.1" || host == "localhost" || host == "10.0.2.2")
        if (!isHttps && !isAllowedLoopbackHttp) return null

        return if (trimmed.endsWith("/api")) trimmed else "$trimmed/api"
    }

    fun encodePathSegment(value: String): String {
        return URLEncoder.encode(value.trim(), Charsets.UTF_8.name()).replace("+", "%20")
    }

    private fun appendValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            is Number -> sb.append(value.toString())
            is String -> appendString(sb, value)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (k == null) continue
                    if (!first) sb.append(',')
                    first = false
                    appendString(sb, k.toString())
                    sb.append(':')
                    appendValue(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in value) {
                    if (!first) sb.append(',')
                    first = false
                    appendValue(sb, item)
                }
                sb.append(']')
            }
            else -> appendString(sb, value.toString())
        }
    }

    private fun appendString(sb: StringBuilder, text: String) {
        sb.append('"')
        for (ch in text) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append(String.format("\\u%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
    }

    private class Parser(private val input: String) {
        private var index = 0

        fun parseRoot(): Any? {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            if (index != input.length) {
                throw IllegalArgumentException("Unexpected trailing JSON content at index $index.")
            }
            return value
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            if (index >= input.length) {
                throw IllegalArgumentException("Unexpected end of JSON input.")
            }
            return when (val ch = input[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("Unexpected character '$ch' at index $index.")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            skipWhitespace()
            val map = linkedMapOf<String, Any?>()
            if (peek('}')) {
                index++
                return map
            }
            while (true) {
                skipWhitespace()
                if (!peek('"')) {
                    throw IllegalArgumentException("Expected object key string at index $index.")
                }
                val key = parseString()
                skipWhitespace()
                expect(':')
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                when {
                    peek(',') -> index++
                    peek('}') -> {
                        index++
                        return map
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}' in JSON object at index $index.")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            skipWhitespace()
            val list = mutableListOf<Any?>()
            if (peek(']')) {
                index++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                when {
                    peek(',') -> index++
                    peek(']') -> {
                        index++
                        return list
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']' in JSON array at index $index.")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (index < input.length) {
                val ch = input[index++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (index >= input.length) {
                            throw IllegalArgumentException("Unterminated escape sequence in JSON string.")
                        }
                        when (val esc = input[index++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (index + 4 > input.length) {
                                    throw IllegalArgumentException("Incomplete unicode escape in JSON string.")
                                }
                                val hex = input.substring(index, index + 4)
                                index += 4
                                val codePoint = hex.toIntOrNull(16)
                                    ?: throw IllegalArgumentException("Invalid unicode escape '\\u$hex'.")
                                sb.append(codePoint.toChar())
                            }
                            else -> throw IllegalArgumentException("Invalid JSON escape '\\$esc'.")
                        }
                    }
                    else -> sb.append(ch)
                }
            }
            throw IllegalArgumentException("Unterminated JSON string.")
        }

        private fun parseBoolean(): Boolean {
            return if (input.startsWith("true", index)) {
                index += 4
                true
            } else if (input.startsWith("false", index)) {
                index += 5
                false
            } else {
                throw IllegalArgumentException("Invalid boolean literal at index $index.")
            }
        }

        private fun parseNull(): Any? {
            if (!input.startsWith("null", index)) {
                throw IllegalArgumentException("Invalid null literal at index $index.")
            }
            index += 4
            return null
        }

        private fun parseNumber(): Number {
            val start = index
            if (input[index] == '-') index++
            while (index < input.length && input[index].isDigit()) index++
            var isFloating = false
            if (index < input.length && input[index] == '.') {
                isFloating = true
                index++
                while (index < input.length && input[index].isDigit()) index++
            }
            if (index < input.length && (input[index] == 'e' || input[index] == 'E')) {
                isFloating = true
                index++
                if (index < input.length && (input[index] == '+' || input[index] == '-')) index++
                while (index < input.length && input[index].isDigit()) index++
            }
            val token = input.substring(start, index)
            return if (isFloating) {
                token.toDoubleOrNull()
                    ?: throw IllegalArgumentException("Invalid JSON floating number '$token'.")
            } else {
                token.toLongOrNull()
                    ?: token.toDoubleOrNull()
                    ?: throw IllegalArgumentException("Invalid JSON integer '$token'.")
            }
        }

        private fun skipWhitespace() {
            while (index < input.length && input[index].isWhitespace()) {
                index++
            }
        }

        private fun peek(expected: Char): Boolean = index < input.length && input[index] == expected

        private fun expect(expected: Char) {
            if (index >= input.length || input[index] != expected) {
                throw IllegalArgumentException("Expected '$expected' at index $index.")
            }
            index++
        }
    }
}

internal fun Map<String, Any?>.stringOrNull(key: String): String? {
    val raw = this[key] ?: return null
    return (raw as? String)?.trim()?.takeIf { it.isNotEmpty() }
}

internal fun Map<String, Any?>.intOrNull(key: String): Int? =
    (this[key] as? Number)?.toInt()

internal fun Map<String, Any?>.longOrNull(key: String): Long? =
    (this[key] as? Number)?.toLong()

internal fun Map<String, Any?>.booleanOrFalse(key: String): Boolean =
    (this[key] as? Boolean) == true

internal fun Map<String, Any?>.objectListOrEmpty(key: String): List<Map<String, Any?>> {
    val list = this[key] as? List<*> ?: return emptyList()
    return list.mapNotNull { item ->
        @Suppress("UNCHECKED_CAST")
        item as? Map<String, Any?>
    }
}

internal fun Map<String, Any?>.objectOrNull(key: String): Map<String, Any?>? {
    @Suppress("UNCHECKED_CAST")
    return this[key] as? Map<String, Any?>
}
