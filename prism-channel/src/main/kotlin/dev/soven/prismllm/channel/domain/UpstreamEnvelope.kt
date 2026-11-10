package dev.soven.prismllm.channel.domain

import dev.soven.prismllm.shared.domain.OAuthProvider

/**
 * 上游请求的外层封装。Gemini 协议本身没有信封；Google Code Assist 把同样的
 * Gemini 请求包在 {model, project, request, ...} 里，响应也包一层 {response}。
 */
enum class UpstreamEnvelope {
    NONE,
    CODE_ASSIST,
}

/** 订阅账号只服务官方端点，provider 决定是否需要信封。 */
val ChannelRecord.upstreamEnvelope: UpstreamEnvelope
    get() = when (provider) {
        OAuthProvider.ANTIGRAVITY -> UpstreamEnvelope.CODE_ASSIST
        OAuthProvider.CHATGPT, null -> UpstreamEnvelope.NONE
    }
