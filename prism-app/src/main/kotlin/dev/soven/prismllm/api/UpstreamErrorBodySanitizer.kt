package dev.soven.prismllm.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val MAX_UPSTREAM_ERROR_BODY_BYTES = 8_192
private const val MAX_INPUT_PRE_TRUNCATE_CHARS = 32_768

private val SECRET_KEY_PATTERN = Regex(
    """(?i)^[\w-]*?(?:api[_-]?key|authorization|token|secret|password)$""",
)

private val UPSTREAM_SECRET_JSON_FIELD = Regex(
    """(?i)("[\w-]*?(?:api[_-]?key|authorization|token|secret|password)"\s*:\s*")((?:[^"\\]|\\.)*+)("?)""",
)
private val UPSTREAM_SECRET_KEY_VALUE = Regex(
    """(?i)((?:^|[\s&?,;])[\w-]*?(?:api[_-]?key|authorization|token|secret|password)\s*[:=]\s*)(?:(?:Bearer|Basic)\s+[A-Za-z0-9._~+/=-]+|"(?:[^"\\]|\\.)*+"?|'(?:[^'\\]|\\.)*+'?|[^\s&,;]+)""",
)
private val UPSTREAM_AUTH_SCHEME_TOKEN = Regex("""(?i)\b(Bearer|Basic)\s+[A-Za-z0-9._~+/=-]+""")
private val UPSTREAM_SK_TOKEN = Regex("""(?i)\bsk-[A-Za-z0-9_-]{8,}\b""")

private val looseJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * 请求日志只保存失败请求的上游错误正文，并在入库前截断、脱敏。
 * 针对 JSON 优先使用 AST 递归脱敏，能完整处理包含转义引号等字符的凭据值；非合法 JSON 降级使用转义感知的正则脱敏。
 *
 * @author 文杰
 * @since 2026-10-29
 */
internal fun sanitizeUpstreamErrorBody(body: String?): String? {
    val trimmed = body?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val bounded = trimmed.take(MAX_INPUT_PRE_TRUNCATE_CHARS)
    val sanitized = sanitizeJsonIfValid(bounded) ?: sanitizeWithRegex(bounded)
    val printable = sanitized.filter { it == '\n' || it == '\r' || it == '\t' || it >= ' ' }
    return truncateUtf8Bytes(printable, MAX_UPSTREAM_ERROR_BODY_BYTES)
}

private fun sanitizeJsonIfValid(text: String): String? {
    if (!text.startsWith("{") && !text.startsWith("[")) {
        return null
    }
    return runCatching {
        val root = looseJson.parseToJsonElement(text)
        if (root !is JsonObject && root !is JsonArray) {
            return null
        }
        val sanitized = sanitizeJsonElement(root)
        looseJson.encodeToString(JsonElement.serializer(), sanitized)
    }.getOrNull()
}

private fun sanitizeJsonElement(element: JsonElement): JsonElement {
    return when (element) {
        is JsonObject -> {
            val newMap = mutableMapOf<String, JsonElement>()
            for ((key, value) in element) {
                if (SECRET_KEY_PATTERN.matches(key)) {
                    newMap[key] = JsonPrimitive("[REDACTED]")
                } else {
                    newMap[key] = sanitizeJsonElement(value)
                }
            }
            JsonObject(newMap)
        }
        is JsonArray -> {
            JsonArray(element.map { sanitizeJsonElement(it) })
        }
        is JsonPrimitive -> {
            if (element.isString) {
                val content = element.content
                val sanitized = sanitizeJsonIfValid(content) ?: sanitizeWithRegex(content)
                if (sanitized != content) JsonPrimitive(sanitized) else element
            } else {
                element
            }
        }
    }
}

private fun sanitizeWithRegex(text: String): String {
    return text
        .replace(UPSTREAM_SECRET_JSON_FIELD) { match ->
            "${match.groupValues[1]}[REDACTED]${match.groupValues[3]}"
        }
        .let { sanitizePlainText(it) }
}

private fun sanitizePlainText(text: String): String {
    return text
        .replace(UPSTREAM_AUTH_SCHEME_TOKEN) { match ->
            "${match.groupValues[1]} [REDACTED]"
        }
        .replace(UPSTREAM_SK_TOKEN, "sk-[REDACTED]")
        .replace(UPSTREAM_SECRET_KEY_VALUE) { match ->
            "${match.groupValues[1]}[REDACTED]"
        }
}

private fun truncateUtf8Bytes(text: String, maxBytes: Int): String {
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return text

    var currentBytes = 0
    val sb = StringBuilder()
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        val charCount = Character.charCount(cp)
        val cpBytes = when {
            cp <= 0x7F -> 1
            cp <= 0x7FF -> 2
            cp <= 0xFFFF -> 3
            else -> 4
        }
        if (currentBytes + cpBytes > maxBytes) {
            break
        }
        currentBytes += cpBytes
        sb.appendCodePoint(cp)
        i += charCount
    }
    return sb.toString()
}
