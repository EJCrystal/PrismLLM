package dev.soven.prismllm.proxy.codeassist

/**
 * Code Assist 用 User-Agent 识别调用方：不带 Antigravity 标识时，Google 会当成
 * 已停止支持的旧客户端——loadCodeAssist 不返回项目，额度接口返回 403
 * SUBSCRIPTION_REQUIRED。运行时应优先使用全局请求头目录里的 antigravity profile，
 * 这里只作为目录尚未加载时的兜底。
 *
 * build 号来自 Antigravity 2.32.1 的实际抓包；官方更新清单只提供版本号，
 * 因此版本可刷新，build 号暂时固定。
 */
internal const val ANTIGRAVITY_CLIENT_BUILD = "1021296516"

internal fun antigravityUserAgent(version: String): String =
    "antigravity/hub/$version (aidev_client; os_type=linux; arch=amd64; cl=$ANTIGRAVITY_CLIENT_BUILD)"

internal val DEFAULT_ANTIGRAVITY_CODE_ASSIST_USER_AGENT = antigravityUserAgent("2.32.1")
