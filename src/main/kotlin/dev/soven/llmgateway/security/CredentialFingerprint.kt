package dev.soven.llmgateway.security

import dev.soven.llmgateway.domain.ChannelKeyRecord
import java.security.MessageDigest

/**
 * 凭据版本指纹：密文、nonce 或密钥版本任一变化都会改变结果。
 * 用于跨步骤操作（例如浏览器登录）中确认目标凭据没有被替换。
 *
 * @author 文杰
 * @since 2026-11-06
 */
fun credentialFingerprint(ciphertext: String, nonce: String, keyVersion: Int): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$keyVersion\u0000$nonce\u0000$ciphertext".toByteArray(Charsets.UTF_8))
    return buildString(digest.size * 2) {
        for (byte in digest) append("%02x".format(byte.toInt() and 0xff))
    }
}

fun ChannelKeyRecord.credentialFingerprint(): String =
    credentialFingerprint(credentialCiphertext, credentialNonce, credentialKeyVersion)
