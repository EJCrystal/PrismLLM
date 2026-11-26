package dev.soven.prismllm.quota.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientKeyIpPolicyTest {
    @Test
    fun emptyListsPreserveUnrestrictedKeys() {
        val policy = ClientKeyIpPolicy()
        assertTrue(policy.isUnrestricted)
        assertTrue(policy.allows("203.0.113.1"))
        assertTrue(policy.allows(null))
    }

    @Test
    fun allowlistOnlyAcceptsMatchingAddresses() {
        val policy = ClientKeyIpPolicy(allowlist = setOf("203.0.113.0/24", "2001:db8::/32"))
        assertTrue(policy.allows("203.0.113.9"))
        assertTrue(policy.allows("2001:db8:1234::1"))
        assertFalse(policy.allows("192.0.2.1"))
        assertFalse(policy.allows("2001:db9::1"))
    }

    @Test
    fun denylistOverridesAllowlistAndMatchesMappedIpv4() {
        val policy = ClientKeyIpPolicy(setOf("203.0.113.0/24"), setOf("203.0.113.9"))
        assertTrue(policy.allows("203.0.113.8"))
        assertFalse(policy.allows("203.0.113.9"))
        assertFalse(policy.allows("::ffff:203.0.113.9"))
        assertFalse(policy.allows("192.0.2.1"))
        assertTrue(ClientKeyIpPolicy(denylist = setOf("203.0.113.9")).allows("192.0.2.1"))
    }

    @Test
    fun restrictedKeysRejectMissingOrInvalidClientIps() {
        for (policy in listOf(ClientKeyIpPolicy(setOf("0.0.0.0/0")), ClientKeyIpPolicy(denylist = setOf("::1")))) {
            for (address in listOf(null, "-", "localhost", "203.0.113.1, 192.0.2.1")) {
                assertFalse(policy.allows(address))
            }
        }
    }

    @Test
    fun normalizesWhitespaceAndRejectsBadRulesOrOversizedLists() {
        assertEquals(setOf("203.0.113.1"), ClientKeyIpPolicy(setOf(" 203.0.113.1 ", "203.0.113.1")).normalized().allowlist)
        assertFailsWith<IllegalArgumentException> { ClientKeyIpPolicy(setOf("")) }
        assertFailsWith<IllegalArgumentException> { ClientKeyIpPolicy(denylist = setOf("hostname")) }
        assertFailsWith<IllegalArgumentException> {
            ClientKeyIpPolicy(allowlist = (0..256).map { "192.0." + (it / 256) + "." + (it % 256) }.toSet())
        }
    }
}
