package dev.soven.prismllm.proxy.codeassist

/**
 * Code Assist 用 User-Agent 识别调用方：不带 Antigravity 标识时，Google 会当成
 * 已停止支持的旧客户端——loadCodeAssist 不返回项目，额度接口返回 403
 * SUBSCRIPTION_REQUIRED。所有管理请求都必须带上这个标识。
 */
internal const val ANTIGRAVITY_CODE_ASSIST_USER_AGENT = "antigravity/hub/2.29.1 linux/amd64"
