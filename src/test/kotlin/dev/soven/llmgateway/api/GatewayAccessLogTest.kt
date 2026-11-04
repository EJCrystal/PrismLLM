package dev.soven.llmgateway.api

import dev.soven.llmgateway.config.TrustedProxyNetwork
import kotlin.test.Test
import kotlin.test.assertEquals

class GatewayAccessLogTest {

    @Test
    fun prefersCloudflareClientIpAndKeepsPeerIp() {
        val metadata = requestAccessLogMetadata(
            requestId = "request-123",
            cloudflareConnectingIp = "203.0.113.7",
            forwardedFor = "198.51.100.4, 172.19.0.2",
            realIp = null,
            forwarded = null,
            peerIp = "172.19.0.2",
            userAgent = "test-client/1.0",
            trustedProxyNetworks = listOf(TrustedProxyNetwork.parse("172.19.0.0/16")),
        )

        assertEquals("request-123", metadata.requestId)
        assertEquals("203.0.113.7", metadata.clientIp)
        assertEquals("cf-connecting-ip", metadata.clientIpSource)
        assertEquals("172.19.0.2", metadata.peerIp)
        assertEquals("test-client/1.0", metadata.userAgent)
    }

    @Test
    fun fallsBackThroughForwardedHeadersToPeerIp() {
        val forwarded = requestAccessLogMetadata(
            requestId = null,
            cloudflareConnectingIp = null,
            forwardedFor = "198.51.100.8, 172.19.0.2",
            realIp = "198.51.100.9",
            forwarded = "for=198.51.100.10",
            peerIp = "172.19.0.2",
            userAgent = null,
            trustedProxyNetworks = listOf(TrustedProxyNetwork.parse("172.19.0.0/16")),
        )
        assertEquals("198.51.100.8", forwarded.clientIp)
        assertEquals("x-forwarded-for", forwarded.clientIpSource)

        val peer = requestAccessLogMetadata(
            requestId = null,
            cloudflareConnectingIp = null,
            forwardedFor = null,
            realIp = null,
            forwarded = null,
            peerIp = "172.19.0.2",
            userAgent = null,
        )
        assertEquals("172.19.0.2", peer.clientIp)
        assertEquals("peer", peer.clientIpSource)
    }

    @Test
    fun sanitizesAndLimitsLogMetadata() {
        val metadata = requestAccessLogMetadata(
            requestId = "  request-123\n",
            cloudflareConnectingIp = null,
            forwardedFor = null,
            realIp = null,
            forwarded = null,
            peerIp = " 172.19.0.2 ",
            userAgent = "agent\r\n" + "x".repeat(600),
        )

        assertEquals("request-123", metadata.requestId)
        assertEquals("172.19.0.2", metadata.clientIp)
        assertEquals("agent%20" + "x".repeat(504), metadata.userAgent)
        assertEquals(512, metadata.userAgent.length)
    }

    @Test
    fun ignoresForwardedHeadersFromUntrustedPeer() {
        val metadata = requestAccessLogMetadata(
            requestId = null,
            cloudflareConnectingIp = "203.0.113.99",
            forwardedFor = "203.0.113.98",
            realIp = "203.0.113.97",
            forwarded = "for=203.0.113.96",
            peerIp = "198.51.100.10",
            userAgent = null,
            trustedProxyNetworks = listOf(TrustedProxyNetwork.parse("172.19.0.0/16")),
        )

        assertEquals("198.51.100.10", metadata.clientIp)
        assertEquals("peer", metadata.clientIpSource)
    }

    @Test
    fun escapesLogFieldDelimitersInUserAgent() {
        val metadata = requestAccessLogMetadata(
            requestId = null,
            cloudflareConnectingIp = null,
            forwardedFor = null,
            realIp = null,
            forwarded = null,
            peerIp = "198.51.100.10",
            userAgent = "agent clientIp=203.0.113.9 clientIpSource=peer",
        )

        assertEquals("agent%20clientIp%3D203.0.113.9%20clientIpSource%3Dpeer", metadata.userAgent)
    }

    @Test
    fun skipsInvalidForwardedValuesAndUsesNextValidHeader() {
        val metadata = requestAccessLogMetadata(
            requestId = null,
            cloudflareConnectingIp = "unknown",
            forwardedFor = "not-an-ip, 198.51.100.8",
            realIp = "198.51.100.9",
            forwarded = "for=198.51.100.10",
            peerIp = "172.19.0.2",
            userAgent = null,
            trustedProxyNetworks = listOf(TrustedProxyNetwork.parse("172.19.0.0/16")),
        )

        assertEquals("198.51.100.8", metadata.clientIp)
        assertEquals("x-forwarded-for", metadata.clientIpSource)
    }

    @Test
    fun parsesForwardedIpv6AddressWithPort() {
        val metadata = requestAccessLogMetadata(
            requestId = null,
            cloudflareConnectingIp = null,
            forwardedFor = null,
            realIp = null,
            forwarded = "for=\"[2001:db8::1]:4711\"",
            peerIp = "172.19.0.2",
            userAgent = null,
            trustedProxyNetworks = listOf(TrustedProxyNetwork.parse("172.19.0.0/16")),
        )

        assertEquals("2001:db8::1", metadata.clientIp)
        assertEquals("forwarded", metadata.clientIpSource)
    }
}
