package dev.soven.prismllm.config

import java.net.URI

/**
 * 网关代执行 Responses 内置 web_search 的配置。
 *
 * 只有启用并且配置了可用的搜索后端时，带 web_search 的请求才会走
 * “合成函数工具 + 多轮搜索”的代理路径；否则保持原有协议转换行为。
 *
 * @author 文杰
 * @since 2026-11-06
 */
data class WebSearchConfig(
    val enabled: Boolean = false,
    val provider: String = "tavily",
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val maxResults: Int = 6,
    val timeoutMs: Long = 15_000,
    val maxRounds: Int = 6,
    val maxPageChars: Int = 1500,
)

/** 支持的搜索后端。 */
val WEB_SEARCH_PROVIDERS = setOf("tavily", "brave", "exa", "deepseek", "searxng")

/** 与 web_search_settings.base_url 列宽保持一致。 */
const val MAX_WEB_SEARCH_BASE_URL_CHARS = 500

/**
 * 校验搜索地址。携带 API 密钥的后端必须使用 HTTPS，避免密钥在明文链路上传输；
 * 本机回环地址保留 HTTP 例外，SearXNG 本身不携带密钥，允许 HTTP。
 *
 * @return 错误说明；null 表示地址可用。
 */
fun webSearchBaseUrlError(
    provider: String,
    baseUrl: String?,
    requireHttps: Boolean = !provider.equals("searxng", ignoreCase = true),
): String? {
    if (baseUrl.isNullOrBlank()) return null
    if (baseUrl.length > MAX_WEB_SEARCH_BASE_URL_CHARS) {
        return "搜索地址不能超过 $MAX_WEB_SEARCH_BASE_URL_CHARS 个字符"
    }
    val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return "搜索地址格式无效"
    if (uri.host.isNullOrBlank()) return "搜索地址必须包含主机名"
    if (uri.rawQuery != null || uri.rawFragment != null) return "搜索地址不能包含查询参数或片段"
    if (uri.scheme.equals("https", ignoreCase = true)) return null
    if (!uri.scheme.equals("http", ignoreCase = true)) return "搜索地址只支持 http 或 https"
    if (!requireHttps || isLoopbackHost(uri.host)) return null
    return "携带 API 密钥的搜索地址必须使用 HTTPS（本机回环地址除外）"
}

private fun isLoopbackHost(host: String): Boolean =
    host.trim().removePrefix("[").removeSuffix("]").lowercase() in setOf("127.0.0.1", "localhost", "::1")
