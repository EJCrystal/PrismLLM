package dev.soven.llmgateway.api

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
        assertEquals("agent " + "x".repeat(506), metadata.userAgent)
    }
}
