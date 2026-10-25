package dev.soven.llmgateway.config

import java.nio.file.Path
import java.util.Base64
import dev.soven.llmgateway.report.RetentionPolicy
import dev.soven.llmgateway.resilience.UpstreamResiliencePolicy

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
    val exposeUpstreamModels: Boolean,
    val retention: RetentionPolicy,
    val resilience: UpstreamResiliencePolicy = UpstreamResiliencePolicy(),
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
            val retryBackoffBaseMs = longValue("RETRY_BACKOFF_BASE_MS", 100)
            val retryBackoffMaxMs = longValue("RETRY_BACKOFF_MAX_MS", 2_000)
            val retryJitterMs = longValue("RETRY_JITTER_MS", 50)
            val circuitFailureThreshold = longValue("CIRCUIT_FAILURE_THRESHOLD", 3).toInt()
            val circuitOpenMs = longValue("CIRCUIT_OPEN_MS", 30_000)
            require(retryBackoffBaseMs in 0..60_000) { "RETRY_BACKOFF_BASE_MS 必须在 0..60000 之间" }
            require(retryBackoffMaxMs in retryBackoffBaseMs..60_000) {
                "RETRY_BACKOFF_MAX_MS 必须在 RETRY_BACKOFF_BASE_MS..60000 之间"
            }
            require(retryJitterMs in 0..60_000) { "RETRY_JITTER_MS 必须在 0..60000 之间" }
            require(circuitFailureThreshold in 1..100) { "CIRCUIT_FAILURE_THRESHOLD 必须在 1..100 之间" }
            require(circuitOpenMs in 1_000..3_600_000) { "CIRCUIT_OPEN_MS 必须在 1000..3600000 之间" }

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
                firstTokenTimeoutMs = longValue("FIRST_TOKEN_TIMEOUT_MS", 60_000),
                idleTimeoutMs = longValue("IDLE_TIMEOUT_MS", 60_000),
                totalTimeoutMs = longValue("TOTAL_TIMEOUT_MS", 600_000),
                maxAttempts = maxAttempts,
                drainTimeoutMs = longValue("DRAIN_TIMEOUT_MS", 30_000),
                staticDir = Path.of(value("STATIC_DIR", "web/dist")),
                cookieSecure = value("COOKIE_SECURE", "false").toBooleanStrictOrNull()
                    ?: error("COOKIE_SECURE 必须是 true 或 false"),
                exposeUpstreamModels = value("EXPOSE_UPSTREAM_MODELS", "false").toBooleanStrictOrNull()
                    ?: error("EXPOSE_UPSTREAM_MODELS 必须是 true 或 false"),
                retention = RetentionPolicy(
                    retentionDays = longValue("REQUEST_LOG_RETENTION_DAYS", 30).toInt(),
                    batchSize = longValue("REQUEST_LOG_CLEANUP_BATCH_SIZE", 5_000).toInt(),
                    cleanupEnabled = value("REQUEST_LOG_CLEANUP_ENABLED", "true").toBooleanStrictOrNull()
                        ?: error("REQUEST_LOG_CLEANUP_ENABLED 必须是 true 或 false"),
                    cleanupIntervalMs = longValue("REQUEST_LOG_CLEANUP_INTERVAL_MS", 3_600_000),
                ),
                resilience = UpstreamResiliencePolicy(
                    backoffBaseMs = retryBackoffBaseMs,
                    backoffMaxMs = retryBackoffMaxMs,
                    jitterMs = retryJitterMs,
                    circuitFailureThreshold = circuitFailureThreshold,
                    circuitOpenMs = circuitOpenMs,
                ),
            )
        }
    }
}
