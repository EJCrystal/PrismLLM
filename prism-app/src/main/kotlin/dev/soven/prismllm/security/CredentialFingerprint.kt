package dev.soven.prismllm.security

import dev.soven.prismllm.channel.domain.ChannelKeyRecord
import dev.soven.prismllm.oauth.domain.OAuthAccountRecord
import dev.soven.prismllm.shared.crypto.credentialFingerprint

fun ChannelKeyRecord.credentialFingerprint(): String =
    credentialFingerprint(credentialCiphertext, credentialNonce, credentialKeyVersion)

fun OAuthAccountRecord.credentialFingerprint(): String? {
    val ciphertext = refreshTokenCiphertext ?: return null
    val nonce = refreshTokenNonce ?: return null
    val keyVersion = refreshTokenKeyVersion ?: return null
    return credentialFingerprint(ciphertext, nonce, keyVersion)
}
