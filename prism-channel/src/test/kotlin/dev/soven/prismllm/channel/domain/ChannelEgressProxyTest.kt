package dev.soven.prismllm.channel.domain

import kotlin.test.Test
import kotlin.test.assertFailsWith

class ChannelEgressProxyTest {
    private val proxies = listOf(
        ChannelEgressProxy("us", "美国", "http://127.0.0.1:7897"),
        ChannelEgressProxy("jp", "日本", "http://localhost:8080"),
        ChannelEgressProxy("socks", "SOCKS5", "socks5://127.0.0.1:1080"),
    )

    @Test
    fun `multiple keys may share one channel proxy and unbound keys remain valid`() {
        validateChannelEgress(proxies, listOf("us", "us", "jp", "socks", null))
        validateChannelEgress(emptyList(), listOf(null))
    }

    @Test
    fun `missing and cross channel proxy assignments are rejected`() {
        assertFailsWith<ChannelEgressConfigurationException> { validateChannelEgress(proxies, listOf("other-channel-proxy")) }
        assertFailsWith<ChannelEgressConfigurationException> { validateChannelEgress(emptyList(), listOf("us")) }
    }

    @Test
    fun `duplicate proxies and unsafe proxy urls are rejected`() {
        assertFailsWith<ChannelEgressConfigurationException> { validateChannelEgress(proxies + proxies.first()) }
        assertFailsWith<ChannelEgressConfigurationException> { validateChannelEgress(proxies + proxies.first().copy(id = "other")) }
        listOf("https://localhost:7897", "socks4://localhost:1080", "socks5h://localhost:1080", "socks5://user:password@localhost:1080", "socks5://localhost/path", "socks5://localhost?x=1", "socks5://localhost:0", "socks5://localhost:65536", "http://user:password@localhost:80", "http://localhost/path", "http://localhost?x=1", "http://localhost#x", "http://localhost:0", "http://localhost:65536", "not-a-url").forEach { url ->
            assertFailsWith<ChannelEgressConfigurationException>(url) { validateChannelEgress(listOf(proxies.first().copy(url = url))) }
        }
    }
}
