package dev.soven.llmgateway.api

private const val MAX_UPSTREAM_ERROR_BODY_BYTES = 8_192

private val UPSTREAM_SECRET_JSON_FIELD = Regex(
    """(?i)("[\w-]*?(?:api[_-]?key|authorization|token|secret|password)"\s*:\s*")[^"\r\n]*("?)""",
)
private val UPSTREAM_SECRET_KEY_VALUE = Regex(
    """(?i)((?:^|[\s&?,;])[\w-]*?(?:api[_-]?key|authorization|token|secret|password)\s*=\s*)([^\s&"'\\]+)""",
)
private val UPSTREAM_BEARER_TOKEN = Regex("""(?i)(Bearer\s+)[A-Za-z0-9._~+/=-]+""")
private val UPSTREAM_SK_TOKEN = Regex("""(?i)\bsk-[A-Za-z0-9_-]{8,}\b""")

/**
 * 请求日志只保存失败请求的上游错误正文，并在入库前截断、脱敏。
 *
 * @author 文杰
 * @since 2026-10-29
 */
internal fun sanitizeUpstreamErrorBody(body: String?): String? {
    val trimmed = body?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val sanitized = trimmed
        .replace(UPSTREAM_SECRET_JSON_FIELD) { match ->
            "${match.groupValues[1]}[REDACTED]${match.groupValues[2]}"
        }
        .replace(UPSTREAM_SECRET_KEY_VALUE) { match ->
            "${match.groupValues[1]}[REDACTED]"
        }
        .replace(UPSTREAM_BEARER_TOKEN) { match ->
            "${match.groupValues[1]}[REDACTED]"
        }
        .replace(UPSTREAM_SK_TOKEN, "sk-[REDACTED]")
    val printable = sanitized.filter { it == '\n' || it == '\r' || it == '\t' || it >= ' ' }
    return truncateUtf8Bytes(printable, MAX_UPSTREAM_ERROR_BODY_BYTES)
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
