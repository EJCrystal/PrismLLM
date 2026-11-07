package dev.soven.llmgateway.config

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
