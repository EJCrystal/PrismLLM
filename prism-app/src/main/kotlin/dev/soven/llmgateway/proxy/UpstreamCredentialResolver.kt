package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.shared.error.GatewayError
import dev.soven.llmgateway.shared.domain.UpstreamCredential
import dev.soven.llmgateway.oauth.KeyCredentialResolver
import dev.soven.llmgateway.oauth.OAuthTokenUnavailableException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException

/**
 * 管理面与探测路径的凭据解析：OAuth 账号取 access token，普通密钥解密静态凭据。
 * 这些路径没有其他候选可切换，失败统一映射为上游凭据不可用。
 *
 * @author 文杰
 * @since 2026-11-06
 */
internal suspend fun resolveSavedCredential(credentials: KeyCredentialResolver, credential: UpstreamCredential): String =
    try {
        credentials.resolveCredential(credential)
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: OAuthTokenUnavailableException) {
        throw GatewayError(
            HttpStatusCode.BadGateway,
            "upstream_credential_invalid",
            cause.message ?: "订阅账号不可用，请重新授权",
            retryable = false,
        )
    } catch (cause: Throwable) {
        if (cause is VirtualMachineError) throw cause
        throw GatewayError(
            HttpStatusCode.BadGateway,
            "upstream_credential_invalid",
            "渠道凭据无法解密",
            retryable = false,
        )
    }
