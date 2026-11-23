package dev.soven.prismllm.channel.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelProxyHostsTest {
    @Test
    fun `entries are normalized deduplicated and support wildcard suffixes`() {
        val hosts = ChannelProxyHosts.parse(
            " Proxy.Example , *.onesproxy.vip,proxy.example,,10.0.0.8 ",
            "CHANNEL_PROXY_ALLOWED_HOSTS",
        )

        assertEquals(listOf("proxy.example", "*.onesproxy.vip", "10.0.0.8"), hosts)
    }

    @Test
    fun `trailing dots are normalized for entries and requested hosts`() {
        assertEquals(listOf("proxy.example", "*.example.com"), ChannelProxyHosts.parse("proxy.example.,*.example.com."))
        assertTrue(ChannelProxyHosts.matches("proxy.example.", listOf("proxy.example")))
        assertTrue(ChannelProxyHosts.matches("na.example.com", listOf("*.example.com.")))
        assertFalse(ChannelProxyHosts.matches("example.com.", listOf("*.example.com.")))
    }

    @Test
    fun `invalid entries cannot smuggle protocol port path or credentials`() {
        listOf(
            "http://proxy.example:8080",
            "proxy.example:8080",
            "proxy.example/path",
            "user@proxy.example",
            "*.example.com:443",
            "**.example.com",
            "*.",
        ).forEach { entry ->
            assertFailsWith<IllegalArgumentException>(entry) {
                ChannelProxyHosts.parse(listOf(entry), "CHANNEL_PROXY_ALLOWED_HOSTS")
            }
        }
        assertFailsWith<IllegalArgumentException> {
            ChannelProxyHosts.parse(List(65) { "host$it.example" }, "CHANNEL_PROXY_ALLOWED_HOSTS")
        }
    }

    @Test
    fun `wildcard matches subdomains but not the suffix itself`() {
        val allowed = listOf("*.onesproxy.vip", "exact.example")
        assertTrue(ChannelProxyHosts.matches("na-data.onesproxy.vip", allowed))
        assertTrue(ChannelProxyHosts.matches("us.data.onesproxy.vip", allowed))
        assertFalse(ChannelProxyHosts.matches("onesproxy.vip", allowed))
        assertFalse(ChannelProxyHosts.matches("evilonesproxy.vip", allowed))
        assertTrue(ChannelProxyHosts.matches("EXACT.example", allowed))
        assertFalse(ChannelProxyHosts.matches("other.example", allowed))
    }

    @Test
    fun `loopback hosts and bracketed ipv6 are always allowed`() {
        assertTrue(ChannelProxyHosts.matches("127.0.0.1", emptyList()))
        assertTrue(ChannelProxyHosts.matches("localhost", emptyList()))
        assertTrue(ChannelProxyHosts.matches("[::1]", emptyList()))
        assertFalse(ChannelProxyHosts.matches("example.com", emptyList()))
        assertFalse(ChannelProxyHosts.matches(null, listOf("example.com")))
    }
}
