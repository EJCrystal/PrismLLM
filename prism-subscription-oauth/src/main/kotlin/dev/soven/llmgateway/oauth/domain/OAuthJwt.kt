package dev.soven.llmgateway.oauth.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Base64

/**
 * access token / id token 中与账号身份有关的公开声明。
 * 只读取 payload，不校验签名：token 来自本网关自己的刷新结果，签名校验由上游负责。
 *
 * @author 文杰
 * @since 2026-11-06
 */
data class OAuthTokenClaims(
    val chatgptAccountId: String? = null,
    val planType: String? = null,
    val email: String? = null,
)

/**
 * 解析 JWT 中的账号声明。非 JWT、payload 不是 JSON 或缺少字段时返回 null。
 * ChatGPT 的声明位于 `https://api.openai.com/auth` 命名空间下。
 */
fun parseOAuthTokenClaims(token: String?): OAuthTokenClaims? {
    val payload = token
        ?.split('.')
        ?.takeIf { it.size >= 2 }
        ?.get(1)
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    val json = runCatching {
        Json.parseToJsonElement(
            String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8),
        ) as? JsonObject
    }.getOrNull() ?: return null
    val auth = json["https://api.openai.com/auth"] as? JsonObject
    val claims = OAuthTokenClaims(
        chatgptAccountId = auth?.string("chatgpt_account_id"),
        planType = auth?.string("chatgpt_plan_type"),
        email = json.string("email"),
    )
    return claims.takeIf { it != OAuthTokenClaims() }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
