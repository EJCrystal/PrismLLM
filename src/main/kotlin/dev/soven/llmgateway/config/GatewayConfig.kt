package dev.soven.llmgateway.config

import java.nio.file.Path
import java.util.Base64
import dev.soven.llmgateway.report.RetentionPolicy
import dev.soven.llmgateway.resilience.CooldownPolicy

/**
 * 网关运行配置。所有值来自环境变量，启动期完成校验，运行期不再读取环境。
 *
 * @author 文杰
 * @since 2026-10-21
 */
data class GatewayConfig(
    val port: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val masterKey: ByteArray,
    val adminUsername: String,
    val adminPassword: String,
    val corsOrigins: List<String>,
    val requestBodyLimitBytes: Long,
    val connectTimeoutMs: Long,
    val firstTokenTimeoutMs: Long,
    val idleTimeoutMs: Long,
    val totalTimeoutMs: Long,
    val maxAttempts: Int,
    val drainTimeoutMs: Long,
    val staticDir: Path,
    val cookieSecure: Boolean,
    val retention: RetentionPolicy,
    val cooldown: CooldownPolicy = CooldownPolicy(),
    val keyAutoRecoveryIntervalMs: Long = 30_000,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): GatewayConfig {
            fun value(name: String, default: String? = null): String =
                env[name]?.takeIf { it.isNotBlank() }
                    ?: default
                    ?: error("缺少必填环境变量 $name")

            fun longValue(name: String, default: Long): Long =
                value(name, default.toString()).toLongOrNull()
                    ?: error("环境变量 $name 必须是整数")

            val masterKeyText = value("GATEWAY_MASTER_KEY")
            val masterKey = runCatching { Base64.getDecoder().decode(masterKeyText) }
                .getOrElse { error("GATEWAY_MASTER_KEY 必须是 Base64 编码的 32 字节密钥") }
            require(masterKey.size == 32) { "GATEWAY_MASTER_KEY 必须是 Base64 编码的 32 字节密钥" }

            val maxAttempts = longValue("MAX_ATTEMPTS", 2).toInt()
            require(maxAttempts in 1..10) { "MAX_ATTEMPTS 必须在 1..10 之间" }
            val cooldownTransientBaseMs = longValue("COOLDOWN_TRANSIENT_BASE_MS", 30_000)
            val cooldownTransientMaxMs = longValue("COOLDOWN_TRANSIENT_MAX_MS", 600_000)
            val cooldownRateLimitDefaultMs = longValue("COOLDOWN_RATE_LIMIT_DEFAULT_MS", 30_000)
            val cooldownRateLimitMaxMs = longValue("COOLDOWN_RATE_LIMIT_MAX_MS", 60_000)
            val cooldownAuthFailureMs = longValue("COOLDOWN_AUTH_FAILURE_MS", 600_000)
            val ttftSlowThresholdMs = longValue("TTFT_SLOW_THRESHOLD_MS", 10_000)
            val ttftSlowStreak = longValue("TTFT_SLOW_STREAK", 3).toInt()
            val keyAutoRecoveryIntervalMs = longValue("KEY_AUTO_RECOVERY_INTERVAL_MS", 30_000)
            require(cooldownTransientBaseMs in 1_000..3_600_000) { "COOLDOWN_TRANSIENT_BASE_MS 必须在 1000..3600000 之间" }
            require(cooldownTransientMaxMs in cooldownTransientBaseMs..86_400_000) {
                "COOLDOWN_TRANSIENT_MAX_MS 必须在 COOLDOWN_TRANSIENT_BASE_MS..86400000 之间"
            }
            require(cooldownRateLimitDefaultMs in 1_000..600_000) { "COOLDOWN_RATE_LIMIT_DEFAULT_MS 必须在 1000..600000 之间" }
            require(cooldownRateLimitMaxMs in cooldownRateLimitDefaultMs..3_600_000) {
                "COOLDOWN_RATE_LIMIT_MAX_MS 必须在 COOLDOWN_RATE_LIMIT_DEFAULT_MS..3600000 之间"
            }
            require(cooldownAuthFailureMs in 10_000..86_400_000) { "COOLDOWN_AUTH_FAILURE_MS 必须在 10000..86400000 之间" }
            require(ttftSlowThresholdMs in 100..600_000) { "TTFT_SLOW_THRESHOLD_MS 必须在 100..600000 之间" }
            require(ttftSlowStreak in 1..100) { "TTFT_SLOW_STREAK 必须在 1..100 之间" }
            require(keyAutoRecoveryIntervalMs in 1_000..3_600_000) {
                "KEY_AUTO_RECOVERY_INTERVAL_MS 必须在 1000..3600000 之间"
            }

            return GatewayConfig(
                port = longValue("PORT", 8080).toInt(),
                databaseUrl = value("DATABASE_URL", "jdbc:postgresql://localhost:5432/llm_gateway"),
                databaseUser = value("DATABASE_USER", "llm_gateway"),
                databasePassword = value("DATABASE_PASSWORD", "llm_gateway"),
                masterKey = masterKey,
                adminUsername = value("ADMIN_USERNAME", "admin"),
                adminPassword = value("ADMIN_PASSWORD"),
                corsOrigins = value("CORS_ORIGINS", "").split(',').map { it.trim() }.filter { it.isNotEmpty() },
                requestBodyLimitBytes = longValue("REQUEST_BODY_LIMIT_BYTES", 10L * 1024 * 1024),
                connectTimeoutMs = longValue("CONNECT_TIMEOUT_MS", 5_000),
                firstTokenTimeoutMs = longValue("FIRST_TOKEN_TIMEOUT_MS", 120_000),
                idleTimeoutMs = longValue("IDLE_TIMEOUT_MS", 60_000),
                totalTimeoutMs = longValue("TOTAL_TIMEOUT_MS", 600_000),
                maxAttempts = maxAttempts,
                drainTimeoutMs = longValue("DRAIN_TIMEOUT_MS", 30_000),
                staticDir = Path.of(value("STATIC_DIR", "web/dist")),
                cookieSecure = value("COOKIE_SECURE", "false").toBooleanStrictOrNull()
                    ?: error("COOKIE_SECURE 必须是 true 或 false"),
                retention = RetentionPolicy(
                    retentionDays = longValue("REQUEST_LOG_RETENTION_DAYS", 30).toInt(),
                    batchSize = longValue("REQUEST_LOG_CLEANUP_BATCH_SIZE", 5_000).toInt(),
                    cleanupEnabled = value("REQUEST_LOG_CLEANUP_ENABLED", "true").toBooleanStrictOrNull()
                        ?: error("REQUEST_LOG_CLEANUP_ENABLED 必须是 true 或 false"),
                    cleanupIntervalMs = longValue("REQUEST_LOG_CLEANUP_INTERVAL_MS", 3_600_000),
                ),
                cooldown = CooldownPolicy(
                    transientBaseMs = cooldownTransientBaseMs,
                    transientMaxMs = cooldownTransientMaxMs,
                    rateLimitDefaultMs = cooldownRateLimitDefaultMs,
                    rateLimitMaxMs = cooldownRateLimitMaxMs,
                    authFailureMs = cooldownAuthFailureMs,
                    ttftSlowThresholdMs = ttftSlowThresholdMs,
                    ttftSlowStreak = ttftSlowStreak,
                ),
                keyAutoRecoveryIntervalMs = keyAutoRecoveryIntervalMs,
            )
        }
    }
}
