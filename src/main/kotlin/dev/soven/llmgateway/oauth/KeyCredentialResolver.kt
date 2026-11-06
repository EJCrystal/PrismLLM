package dev.soven.llmgateway.oauth

import dev.soven.llmgateway.domain.ChannelKeyRecord

/**
 * 按密钥解析发往上游的凭据：普通密钥返回静态凭据，订阅账号返回 access token。
 * 调用方不应自行解密 `channel_keys`，否则订阅账号会把 refresh token 发给上游。
 *
 * @author 文杰
 * @since 2026-11-06
 */
fun interface KeyCredentialResolver {
    suspend fun resolveCredential(key: ChannelKeyRecord): String
}
