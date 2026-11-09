package dev.soven.llmgateway.oauth

import dev.soven.llmgateway.config.OAuthConfig
import dev.soven.llmgateway.oauth.infra.AntigravityOAuthProvider
import dev.soven.llmgateway.oauth.infra.ChatGptOAuthProvider
import dev.soven.llmgateway.oauth.infra.OAuthTokenProvider

/** 按运行配置装配订阅账号的 token 刷新器。 */
fun buildOAuthTokenProviders(config: OAuthConfig): List<OAuthTokenProvider> = buildList {
    add(ChatGptOAuthProvider(config.chatGpt, config.requestTimeoutMs, config.proxyAllowedHosts))
    add(AntigravityOAuthProvider(config.antigravity, config.requestTimeoutMs, config.proxyAllowedHosts))
}