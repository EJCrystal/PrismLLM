package dev.soven.llmgateway.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CredentialFingerprintTest {
    @Test
    fun `fingerprint changes whenever any credential component changes`() {
        val base = credentialFingerprint("cipher", "nonce", 1)

        assertEquals(base, credentialFingerprint("cipher", "nonce", 1))
        // 直接替换密文但保留 nonce 也必须被识别出来。
        assertNotEquals(base, credentialFingerprint("rotated", "nonce", 1))
        assertNotEquals(base, credentialFingerprint("cipher", "other-nonce", 1))
        assertNotEquals(base, credentialFingerprint("cipher", "nonce", 2))
    }
}
