package dev.soven.prismllm.shared.net

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IpNetworkTest {
    @Test
    fun matchesIpv4HostsAndPartialByteNetworks() {
        val host = IpNetwork.parse("203.0.113.9")
        assertTrue(host.contains("203.0.113.9"))
        assertFalse(host.contains("203.0.113.10"))
        val network = IpNetwork.parse("192.0.2.200/25")
        assertTrue(network.contains("192.0.2.128"))
        assertTrue(network.contains("192.0.2.255"))
        assertFalse(network.contains("192.0.2.127"))
        assertFalse(network.contains("192.0.3.200"))
    }

    @Test
    fun matchesIpv6AndPreservesAddressFamilies() {
        val network = IpNetwork.parse("2001:db8:abcd::1/33")
        assertTrue(network.contains("2001:db8:8000::"))
        assertTrue(network.contains("2001:db8:ffff::ffff"))
        assertFalse(network.contains("2001:db8:7fff::"))
        assertFalse(network.contains("203.0.113.1"))
        assertTrue(IpNetwork.parse("::/0").contains("2001:db9::1"))
        assertFalse(IpNetwork.parse("::/0").contains("192.0.2.1"))
        assertTrue(IpNetwork.parse("0.0.0.0/0").contains("192.0.2.1"))
    }

    @Test
    fun normalizesIpv4MappedIpv6Addresses() {
        assertTrue(IpNetwork.parse("192.0.2.0/24").contains("::ffff:192.0.2.9"))
        assertTrue(IpNetwork.parse("::ffff:192.0.2.0/120").contains("192.0.2.9"))
        assertTrue(IpNetwork.parse("::ffff:c000:0209").contains("192.0.2.9"))
        assertFalse(IpNetwork.parse("::ffff:192.0.2.0/120").contains("192.0.3.1"))
    }

    @Test
    fun rejectsNonLiteralAddressesAndMalformedNetworks() {
        val invalid = listOf(
            "", "localhost", "example.com", "127.1", "1234.0.0.1", "256.0.0.1",
            "192.0.2.1:443", "[::1]", "fe80::1%eth0", "2001:db8::zz", "192.0.2.1/33",
            "::1/129", "192.0.2.1/-1", "192.0.2.1/+1", "192.0.2.1/", "::/64/64", "::ffff:192.0.2.0/95",
        )
        invalid.forEach { value -> assertFailsWith<IllegalArgumentException>(value) { IpNetwork.parse(value) } }
        assertFalse(IpNetwork.isIpAddress(null))
        assertFalse(IpNetwork.parse("0.0.0.0/0").contains("localhost"))
        assertFalse(IpNetwork.parse("::/0").contains(null))
    }
}
