package dev.soven.prismllm.shared.domain

/**
 * 按上游凭据解析发往上游的凭据：静态密钥返回解密后的密钥，订阅账号返回 access token。
 * 调用方不应自行解密，否则订阅账号会把 refresh token 发给上游。
 *
 * @author 文杰
 * @since 2026-11-06
 */
fun interface KeyCredentialResolver {
    suspend fun resolveCredential(credential: UpstreamCredential): String
}
