package dev.soven.prismllm.oauth.domain

/**
 * 单个 OAuth 提供方的公开客户端参数。client secret 只从内置常量或环境变量读取，
 * 不落库、不写日志；安装型客户端的 secret 是公开值，不是用户凭据。
 *
 * @author 文杰
 * @since 2026-11-06
 */
data class OAuthProviderConfig(
    val clientId: String,
    val clientSecret: String? = null,
    val tokenEndpoint: String,
    /** 上游额度查询地址；为空表示该提供方不支持额度观测。 */
    val usageUrl: String? = null,
    /** 交互式登录授权地址；为空表示该提供方不支持从网关发起登录。 */
    val authorizeUrl: String? = null,
    /** 交互式登录回调地址，必须与 OAuth 客户端登记的一致。 */
    val redirectUri: String? = null,
    /** 交互式登录申请的 scope；为空时由提供方决定。 */
    val loginScopes: String? = null,
) {
    val supportsLogin: Boolean get() = authorizeUrl != null && redirectUri != null
}