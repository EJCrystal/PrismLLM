package dev.soven.llmgateway.shared.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 管理员密码的 Argon2id 哈希与校验。编码格式包含参数，后续可平滑调整强度。
 *
 * @author 文杰
 * @since 2026-10-21
 */
object PasswordHasher {
    private const val ITERATIONS = 3
    private const val MEMORY_KB = 65_536
    private const val PARALLELISM = 1
    private const val HASH_BYTES = 32
    private val random = SecureRandom()

    fun hash(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val hash = derive(password, salt, ITERATIONS, MEMORY_KB, PARALLELISM)
        return listOf(
            "argon2id",
            "v=19",
            "m=$MEMORY_KB,t=$ITERATIONS,p=$PARALLELISM",
            Base64.getEncoder().encodeToString(salt),
            Base64.getEncoder().encodeToString(hash),
        ).joinToString("$")
    }

    fun verify(password: String, encoded: String): Boolean {
        val parts = encoded.split('$')
        if (parts.size != 5 || parts[0] != "argon2id") return false
        val params = parts[2].split(',').associate {
            val (key, value) = it.split('=', limit = 2)
            key to value.toInt()
        }
        val salt = Base64.getDecoder().decode(parts[3])
        val expected = Base64.getDecoder().decode(parts[4])
        val actual = derive(
            password,
            salt,
            params["t"] ?: ITERATIONS,
            params["m"] ?: MEMORY_KB,
            params["p"] ?: PARALLELISM,
            expected.size,
        )
        return MessageDigest.isEqual(expected, actual)
    }

    private fun derive(
        password: String,
        salt: ByteArray,
        iterations: Int,
        memoryKb: Int,
        parallelism: Int,
        hashBytes: Int = HASH_BYTES,
    ): ByteArray {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withIterations(iterations)
            .withMemoryAsKB(memoryKb)
            .withParallelism(parallelism)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(parameters)
        return ByteArray(hashBytes).also { generator.generateBytes(password.toCharArray(), it) }
    }
}
