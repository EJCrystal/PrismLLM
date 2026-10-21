package dev.soven.llmgateway.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 调用方密钥的生成与哈希。密钥本身是高熵随机值，服务端只保存 SHA-256 哈希。
 *
 * @author 文杰
 * @since 2026-10-21
 */
object ClientKeyHasher {
    const val PREFIX = "sk-gw-"
    private const val KEY_BYTES = 32
    private val random = SecureRandom()

    fun generate(): GeneratedClientKey {
        val randomPart = ByteArray(KEY_BYTES).also(random::nextBytes)
        val raw = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(randomPart)
        return GeneratedClientKey(
            raw = raw,
            hash = hash(raw),
            prefix = raw.take(PREFIX.length + 6),
        )
    }

    fun hash(raw: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/**
 * 新生成的调用方密钥。明文只返回一次。
 *
 * @author 文杰
 * @since 2026-10-21
 */
data class GeneratedClientKey(
    val raw: String,
    val hash: String,
    val prefix: String,
)
