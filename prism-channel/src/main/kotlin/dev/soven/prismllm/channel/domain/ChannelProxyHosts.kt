package dev.soven.prismllm.channel.domain

/**
 * 渠道出口代理的主机放行规则。
 *
 * 条目可以是精确主机名或 IPv4，也可以是 `*.example.com` 形式的通配后缀；
 * 通配只匹配子域名，不匹配后缀本身。本机回环地址始终允许。
 *
 * @author 文杰
 * @since 2026-11-23
 */
object ChannelProxyHosts {
    const val MAX_ALLOWED_HOSTS = 64

    private const val WILDCARD_PREFIX = "*."
    private val loopbackHosts = setOf("127.0.0.1", "localhost", "::1")
    private val hostPattern = Regex("[a-z0-9][a-z0-9._-]*")

    fun parse(entries: Iterable<String>, setting: String = "渠道出口代理白名单"): List<String> {
        // 尾点在 DNS 中表示绝对域名；统一去掉后再去重和比较，避免保存后匹配不到。
        val normalized = entries.map(::normalizeEntry).filter { it.isNotEmpty() }.distinct()
        require(normalized.size <= MAX_ALLOWED_HOSTS) { "$setting 最多允许 $MAX_ALLOWED_HOSTS 个主机" }
        normalized.forEach { host ->
            require(isValidEntry(host)) {
                "$setting 只接受主机名、IPv4 或 *.域名 通配，不能包含协议、端口、路径或凭据：$host"
            }
        }
        return normalized
    }

    fun isValidEntry(entry: String): Boolean {
        val host = normalizeEntry(entry)
        if (host.startsWith(WILDCARD_PREFIX)) {
            val suffix = host.removePrefix(WILDCARD_PREFIX)
            return suffix.length <= 253 && '.' in suffix && hostPattern.matches(suffix)
        }
        return host.length <= 253 && hostPattern.matches(host)
    }

    fun matches(host: String?, allowedHosts: Collection<String>): Boolean {
        val normalized = host?.takeIf { it.isNotBlank() }?.let(::normalizeHost) ?: return false
        if (normalized in loopbackHosts) return true
        return allowedHosts.any { entry -> matchesEntry(normalized, normalizeEntry(entry)) }
    }

    private fun normalizeEntry(entry: String): String = entry.trim().lowercase().removeSuffix(".")

    private fun matchesEntry(host: String, entry: String): Boolean {
        if (entry.startsWith(WILDCARD_PREFIX)) {
            val suffix = entry.removePrefix(WILDCARD_PREFIX)
            return suffix.isNotEmpty() && host.length > suffix.length + 1 && host.endsWith(".$suffix")
        }
        return host == entry
    }

    /** Java 的 URI 把 IPv6 字面量保留为 `[::1]`，比较前去掉方括号并统一小写。 */
    private fun normalizeHost(host: String): String =
        host.trim().removePrefix("[").removeSuffix("]").lowercase().removeSuffix(".")
}
