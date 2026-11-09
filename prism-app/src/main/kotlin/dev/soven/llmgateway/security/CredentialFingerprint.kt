package dev.soven.llmgateway.security

import dev.soven.llmgateway.domain.ChannelKeyRecord
import dev.soven.llmgateway.domain.OAuthAccountRecord
import dev.soven.llmgateway.shared.crypto.credentialFingerprint

fun ChannelKeyRecord.credentialFingerprint(): String =
    credentialFingerprint(credentialCiphertext, credentialNonce, credentialKeyVersion)

fun OAuthAccountRecord.credentialFingerprint(): String =
    credentialFingerprint(refreshTokenCiphertext, refreshTokenNonce, refreshTokenKeyVersion)
