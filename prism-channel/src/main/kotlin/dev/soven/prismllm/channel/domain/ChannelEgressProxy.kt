package dev.soven.prismllm.channel.domain

import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
data class ChannelEgressProxy(
    val id: String,
    val name: String,
    val url: String,
    val authentication: ChannelProxyAuthentication? = null,
)

@Serializable
data class ChannelProxyAuthentication(
    val username: String,
    val passwordCiphertext: String,
    val passwordNonce: String,
    val passwordKeyVersion: Int,
) {
    override fun toString(): String = "ChannelProxyAuthentication([REDACTED])"
}

open class ChannelEgressConfigurationException(message: String) : IllegalArgumentException(message)

/**
 * 代理主机不在放行名单内。单独成类型，管理台据此提供“放行此主机”入口。
 */
class ChannelProxyHostNotAllowedException(val host: String) : ChannelEgressConfigurationException(
    "渠道代理主机「$host」不在允许列表内；请在「系统配置 → 渠道出口代理」放行该主机，或配置 CHANNEL_PROXY_ALLOWED_HOSTS",
)

fun channelProxyUri(url: String): URI {
    val uri = runCatching { URI(url) }.getOrNull()
    if (url.length > 500 || uri == null || uri.scheme !in setOf("http", "socks5") || uri.host.isNullOrBlank() ||
        uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
        (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") || (uri.port != -1 && uri.port !in 1..65535)
    ) {
        throw ChannelEgressConfigurationException("出口代理必须是 http://主机:端口 或 socks5://主机:端口，地址不能超过 500 个字符；账号密码请单独填写，地址不能包含路径、查询参数或账号密码")
    }
    return uri
}

fun validateChannelEgress(proxies: List<ChannelEgressProxy>, assignments: List<String?> = emptyList()) {
    fun ensure(valid: Boolean, message: String) {
        if (!valid) throw ChannelEgressConfigurationException(message)
    }
    ensure(proxies.size <= 100, "每个渠道最多配置 100 个出口代理")
    ensure(proxies.map { it.id }.distinct().size == proxies.size, "出口代理 ID 不能重复")
    ensure(proxies.map { it.name }.distinct().size == proxies.size, "出口代理名称不能重复")
    proxies.forEach { proxy ->
        ensure(proxy.id.isNotBlank() && proxy.id.length <= 36, "出口代理 ID 长度必须在 1..36 之间")
        ensure(proxy.name.isNotBlank() && proxy.name.length <= 100, "出口代理名称长度必须在 1..100 之间")
        channelProxyUri(proxy.url)
    }
    val ids = proxies.map { it.id }.toSet()
    ensure(assignments.all { it == null || it in ids }, "密钥绑定的出口代理不存在于当前渠道；删除代理前请解除或修改密钥绑定")
}
