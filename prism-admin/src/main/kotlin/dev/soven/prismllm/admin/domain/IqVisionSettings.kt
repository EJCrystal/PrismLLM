package dev.soven.prismllm.admin.domain

import java.net.URI

/**
 * 管理台保存的 IQ 视觉判分配置。apiKey 为解密后的明文，只在服务端内存中流转，
 * 不会返回管理台。没有保存过配置时沿用环境变量默认值。
 *
 * @author 文杰
 * @since 2026-11-08
 */
data class IqVisionSettingsRecord(
    val enabled: Boolean,
    val baseUrl: String,
    val model: String,
    val apiKey: String?,
    val timeoutMs: Long,
    val updatedAt: Long,
    /** 密文存在但无法解密时为 true；此时 apiKey 为 null，管理员需要重新填写。 */
    val apiKeyUnreadable: Boolean = false,
)

/** 视觉判分实际使用的模型端点；由管理台设置覆盖部署默认值。 */
data class IqVisionConfig(
    val enabled: Boolean,
    val baseUrl: String,
    val model: String,
    val apiKey: String?,
    val timeoutMs: Long,
) {
    /** 端点可解析为 http(s) 地址且带主机名时才可能调用成功。 */
    val usable: Boolean
        get() = parseVisionEndpoint(baseUrl) != null
}

/**
 * 判断两个端点是否指向同一服务。
 *
 * 协议与主机名大小写不敏感（`HTTP://Example.COM` 与 `http://example.com` 等价），
 * 但路径大小写敏感：同一主机下的 `/TenantA/v1` 与 `/tenanta/v1` 可能是不同租户，
 * 整体忽略大小写会把后者当成前者，把前者的密钥发给后者。
 */
fun sameVisionEndpoint(left: String, right: String): Boolean {
    val a = parseVisionEndpoint(left) ?: return false
    val b = parseVisionEndpoint(right) ?: return false
    return a == b
}

/**
 * 校验规则与判分时的可用性判断共用这一份解析，避免「保存放行、运行时静默失败」。
 *
 * [query] 参与是否同一端点的比较：多租户代理常用查询参数选租户，
 * `?tenant=a` 与 `?tenant=b` 会被路由到不同服务，忽略它等于把 A 的密钥发给 B。
 */
private data class EndpointParts(val scheme: String, val authority: String, val path: String, val query: String?)

/**
 * 解析 http(s) 端点；非法地址返回 null。
 *
 * 只接受 http/https、必须带主机名，显式端口必须是 1..65535 的数字，不接受用户信息（`user@host`）。
 * 用 [URI] 解析，主机统一小写，路径与查询参数保持原样（均大小写敏感）。
 */
private fun parseVisionEndpoint(raw: String): EndpointParts? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    return runCatching {
        val uri = URI(trimmed)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host
        if (host.isNullOrBlank()) return null
        // URI 对空格、控制字符这类非法输入会抛出异常；这里再兜一次，避免无法发起请求的地址被当成有效端点。
        if (host.any { it.isWhitespace() || it.isISOControl() }) return null
        val port = uri.port
        if (port != -1 && port !in 1..65535) return null
        val rawAuthority = trimmed.substringAfter("://", "").substringBefore('/')
        if (rawAuthority.any { it.isWhitespace() || it.isISOControl() }) return null
        // 用户信息会随请求一起发出去，不属于端点的一部分，直接拒绝而不是悄悄丢弃。
        if (uri.rawUserInfo != null) return null
        val authority = (if (port == -1) host else "$host:$port").lowercase()
        // 只去掉末尾斜杠：连内部斜杠一起折叠会把 /tenant//v1 和 /tenant/v1 当成同一端点，
        // 而实际请求用的仍是原样的 baseUrl，服务端完全可能把它们路由到不同服务。
        val path = uri.rawPath.orEmpty().trimEnd('/')
        EndpointParts(scheme, authority, path, uri.rawQuery)
    }.getOrNull()
}

/**
 * 合并管理台设置与环境变量默认值。
 *
 * 密钥与端点绑定：只有管理台端点与环境变量端点一致时才回退到环境变量里的密钥。
 * 否则改了端点却仍拿环境变量密钥去请求，就等于把凭据发给另一个服务。
 *
 * 保存过密钥但无法解密时同样不回退：管理员显式配置过的密钥坏了，应该提示重填，
 * 而不是静默换成另一个账号的凭据继续跑——那会在 401 时才暴露。
 */
fun IqVisionSettingsRecord?.effective(defaults: IqVisionConfig): IqVisionConfig = this?.let { stored ->
    val baseUrl = stored.baseUrl.ifBlank { defaults.baseUrl }
    val sameBaseUrl = sameVisionEndpoint(baseUrl, defaults.baseUrl)
    IqVisionConfig(
        enabled = stored.enabled,
        baseUrl = baseUrl,
        model = stored.model.ifBlank { defaults.model },
        apiKey = when {
            stored.apiKey != null -> stored.apiKey
            stored.apiKeyUnreadable -> null
            sameBaseUrl -> defaults.apiKey
            else -> null
        },
        timeoutMs = stored.timeoutMs,
    )
} ?: defaults
