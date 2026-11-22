package dev.soven.prismllm.proxy

import java.io.DataInputStream
import java.io.IOException
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

internal class AuthenticatedSocks5SocketFactory(
    private val proxyAddress: InetSocketAddress,
    private val credentials: ProxyCredentials,
    private val handshakeTimeoutMs: Int,
) : SocketFactory() {
    override fun createSocket(): Socket = AuthenticatedSocks5Socket(proxyAddress, credentials, handshakeTimeoutMs)

    override fun createSocket(host: String, port: Int): Socket = createSocket().apply {
        connect(InetSocketAddress.createUnresolved(host, port))
    }

    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket = createSocket().apply {
        bind(InetSocketAddress(localHost, localPort))
        connect(InetSocketAddress.createUnresolved(host, port))
    }

    override fun createSocket(host: InetAddress, port: Int): Socket = createSocket(host.hostAddress, port)

    override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        createSocket(host.hostAddress, port, localHost, localPort)
}

private class AuthenticatedSocks5Socket(
    private val proxyAddress: InetSocketAddress,
    private val credentials: ProxyCredentials,
    private val handshakeTimeoutMs: Int,
) : Socket() {
    override fun connect(endpoint: SocketAddress) = connect(endpoint, handshakeTimeoutMs)

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        val destination = endpoint as? InetSocketAddress ?: throw IOException("SOCKS5 目标地址无效")
        val previousTimeout = soTimeout
        try {
            super.connect(proxyAddress, timeout)
            soTimeout = timeout.takeIf { it > 0 } ?: handshakeTimeoutMs
            negotiate(destination)
            soTimeout = previousTimeout
        } catch (cause: Exception) {
            runCatching { close() }
            throw IOException("SOCKS5 代理认证或连接失败", cause)
        }
    }

    private fun negotiate(destination: InetSocketAddress) {
        val input = DataInputStream(getInputStream())
        val output = getOutputStream()
        output.write(byteArrayOf(5, 1, 2))
        output.flush()
        if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 2) throw IOException("SOCKS5 代理不接受账号密码认证")
        val username = credentials.username.toByteArray(Charsets.UTF_8)
        val password = credentials.password.toByteArray(Charsets.UTF_8)
        if (username.size !in 1..255 || password.size !in 1..255) throw IOException("SOCKS5 认证凭据长度无效")
        output.write(1)
        output.write(username.size)
        output.write(username)
        output.write(password.size)
        output.write(password)
        output.flush()
        if (input.readUnsignedByte() != 1 || input.readUnsignedByte() != 0) throw IOException("SOCKS5 代理认证失败")
        output.write(byteArrayOf(5, 1, 0))
        val host = destination.hostString.removePrefix("[").removeSuffix("]")
        when {
            ':' in host -> {
                output.write(4)
                output.write(InetAddress.getByName(host).address)
            }
            host.matches(Regex("[0-9]+(?:\\.[0-9]+){3}")) -> {
                output.write(1)
                output.write(InetAddress.getByName(host).address)
            }
            else -> {
                val address = IDN.toASCII(host).toByteArray(Charsets.US_ASCII)
                if (address.size !in 1..255) throw IOException("SOCKS5 目标域名长度无效")
                output.write(3)
                output.write(address.size)
                output.write(address)
            }
        }
        output.write(destination.port ushr 8)
        output.write(destination.port and 255)
        output.flush()
        if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 0 || input.readUnsignedByte() != 0) throw IOException("SOCKS5 代理无法连接目标")
        when (input.readUnsignedByte()) {
            1 -> input.skipNBytes(4)
            3 -> input.skipNBytes(input.readUnsignedByte().toLong())
            4 -> input.skipNBytes(16)
            else -> throw IOException("SOCKS5 代理返回的地址类型无效")
        }
        input.readUnsignedShort()
    }
}
