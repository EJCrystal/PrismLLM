package dev.soven.llmgateway.proxy.codeassist

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.util.concurrent.ConcurrentHashMap

/**
 * Code Assist 的请求必须与 token 刷新走同一个账号出口：账号配置了代理时，
 * project 发现、模型目录与额度读取都要经该代理发出。否则会出现「刷新经代理成功、
 * 调用却从网关直连出去」的情况，在只能经代理访问 Google 的部署里直接失败，
 * 也会暴露网关的源 IP。
 *
 * 代理地址由调用方用 OAuth 的 proxyAddress 解析，约束与刷新请求一致。
 *
 * @author 文杰
 * @since 2026-11-08
 */
class CodeAssistClientPool(
    private val direct: HttpClient,
    private val timeoutMs: Long = 30_000,
    /** 测试可替换为计数或桩客户端。 */
    private val create: (InetSocketAddress) -> HttpClient = { proxy -> client(proxy, timeoutMs) },
) : AutoCloseable {
    private val proxied = ConcurrentHashMap<String, HttpClient>()

    /** 无代理时共用应用客户端；有代理时每个出口一条连接池，避免每次请求重建。 */
    fun client(proxy: InetSocketAddress?): HttpClient =
        proxy?.let { address ->
            proxied.computeIfAbsent(address.hostString + ":" + address.port) { create(address) }
        } ?: direct

    override fun close() {
        proxied.values.forEach { runCatching { it.close() } }
        proxied.clear()
    }

    companion object {
        /** 账号代理只支持无认证 HTTP 正向代理，与 OAuth 刷新保持一致。 */
        fun client(proxy: InetSocketAddress, timeoutMs: Long): HttpClient = HttpClient(Java) {
            engine {
                config {
                    // 与 OAuth 刷新一致：目标主机按账号代理选择出口。
                    proxy(ProxySelector.of(proxy))
                }
            }
            install(HttpTimeout) {
                requestTimeoutMillis = timeoutMs
                connectTimeoutMillis = minOf(timeoutMs, 10_000)
                socketTimeoutMillis = timeoutMs
            }
        }
    }
}
