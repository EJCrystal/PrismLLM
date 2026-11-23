package dev.soven.prismllm.api

import dev.soven.prismllm.channel.domain.ChannelEgressConfigurationException
import dev.soven.prismllm.channel.domain.ChannelKeyRecord
import dev.soven.prismllm.channel.domain.ChannelEgressProxy
import dev.soven.prismllm.channel.domain.ChannelProxyAuthentication
import dev.soven.prismllm.channel.domain.ChannelProxyHostNotAllowedException
import dev.soven.prismllm.channel.domain.ChannelRecord
import dev.soven.prismllm.channel.domain.channelProxyUri
import dev.soven.prismllm.channel.domain.validateChannelEgress
import dev.soven.prismllm.proxy.ChannelProxy
import dev.soven.prismllm.proxy.ProxyCredentials
import dev.soven.prismllm.shared.domain.UpstreamCredential
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

internal fun validateChannelProxyHosts(app: GatewayApplication, request: ChannelRequest) {
    val proxies = request.egressProxies ?: return
    validateChannelEgress(proxies.map { ChannelEgressProxy(it.id, it.name, it.url) })
    proxies.forEach { parseChannelProxy(app, it.url) }
}

internal fun channelEgressProxy(app: GatewayApplication, channel: ChannelRecord, credential: UpstreamCredential): ChannelProxy? {
    val proxyId = (credential as? ChannelKeyRecord)?.egressProxyId ?: return null
    val proxy = channel.egressProxies.firstOrNull { it.id == proxyId }
        ?: throw ChannelEgressConfigurationException("密钥绑定的出口代理不存在于当前渠道")
    return parseChannelProxy(app, proxy)
}

internal fun normalizeChannelProxies(
    app: GatewayApplication,
    requests: List<ChannelEgressProxyRequest>,
    existing: List<ChannelEgressProxy> = emptyList(),
): List<ChannelEgressProxy> {
    val previous = existing.associateBy { it.id }
    return requests.map { request ->
        if (request.clearAuthentication && (!request.username.isNullOrEmpty() || !request.password.isNullOrEmpty())) {
            throw ChannelEgressConfigurationException("不能同时填写和清除代理认证信息")
        }
        val uri = channelProxyUri(request.url)
        val previousProxy = previous[request.id]
        val savedAuthentication = previousProxy?.authentication
        val authentication = if (request.clearAuthentication) null else {
            val password = request.password?.takeIf { it.isNotEmpty() }
            val old = previousProxy?.let { previousEndpoint ->
                previousEndpoint.authentication?.takeIf { uri.sameChannelProxyEndpoint(channelProxyUri(previousEndpoint.url)) }
            }
            if (savedAuthentication != null && old == null && password == null) {
                throw ChannelEgressConfigurationException("代理地址已变更，请重新填写代理密码或关闭认证")
            }
            val username = request.username ?: old?.username
            if (username == null && password == null && old == null) null else {
                if (username.isNullOrBlank() || username.toByteArray(Charsets.UTF_8).size > 255 || username.any { it.isISOControl() } ||
                    (uri.scheme == "http" && ':' in username)
                ) throw ChannelEgressConfigurationException("代理账号必须为 1..255 字节，不能包含控制字符；HTTP 代理账号不能包含冒号")
                if (password != null && (password.toByteArray(Charsets.UTF_8).size > 255 || password.any { it.isISOControl() })) {
                    throw ChannelEgressConfigurationException("代理密码必须为 1..255 字节，不能包含控制字符")
                }
                val encrypted = password?.let { app.credentialCipher.encrypt(it, proxyPasswordAad(request.id)) }
                if (encrypted == null && old == null) throw ChannelEgressConfigurationException("启用代理认证时必须填写账号和密码")
                ChannelProxyAuthentication(
                    username,
                    encrypted?.ciphertext ?: old!!.passwordCiphertext,
                    encrypted?.nonce ?: old!!.passwordNonce,
                    encrypted?.keyVersion ?: old!!.passwordKeyVersion,
                )
            }
        }
        ChannelEgressProxy(request.id, request.name, request.url, authentication)
    }.also { validateChannelEgress(it) }
}

internal fun parseChannelProxy(app: GatewayApplication, url: String): ChannelProxy = parseChannelProxy(app, ChannelEgressProxy("", "", url))

internal fun parseChannelProxy(app: GatewayApplication, proxy: ChannelEgressProxy): ChannelProxy {
    val uri = channelProxyUri(proxy.url)
    if (!app.channelProxyHostAllowlist.allows(uri.host)) {
        throw ChannelProxyHostNotAllowedException(uri.host?.removePrefix("[")?.removeSuffix("]") ?: proxy.url)
    }
    val address = InetSocketAddress.createUnresolved(uri.host.removePrefix("[").removeSuffix("]"), uri.channelProxyPort())
    val type = if (uri.scheme == "socks5") Proxy.Type.SOCKS else Proxy.Type.HTTP
    val credentials = proxy.authentication?.let { authentication ->
        val password = try {
            app.credentialCipher.decrypt(authentication.passwordCiphertext, authentication.passwordNonce, proxyPasswordAad(proxy.id))
        } catch (cause: Exception) {
            throw ChannelEgressConfigurationException("代理认证凭据无法解密，请重新保存密码")
        }
        ProxyCredentials(authentication.username, password)
    }
    return ChannelProxy(type, address, credentials)
}

private fun URI.channelProxyPort(): Int = port.takeIf { it != -1 } ?: if (scheme == "socks5") 1080 else 80

private fun URI.sameChannelProxyEndpoint(other: URI): Boolean =
    scheme == other.scheme && host.equals(other.host, ignoreCase = '%' !in host) && channelProxyPort() == other.channelProxyPort()

private fun proxyPasswordAad(proxyId: String): ByteArray = ("channel-egress-proxy:" + proxyId).toByteArray(Charsets.UTF_8)
