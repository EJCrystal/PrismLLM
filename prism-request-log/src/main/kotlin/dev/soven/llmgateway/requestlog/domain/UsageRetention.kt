package dev.soven.llmgateway.requestlog.domain

/** 请求日志保留期与清理批次配置。 */
data class RetentionPolicy(
    val retentionDays: Int = 30,
    val batchSize: Int = 5_000,
    val cleanupEnabled: Boolean = true,
    val cleanupIntervalMs: Long = 60 * 60 * 1000L,
) {
    init {
        require(retentionDays in 1..3650) { "请求日志保留天数必须在 1..3650 之间" }
        require(batchSize in 100..100_000) { "清理批次大小必须在 100..100000 之间" }
        require(cleanupIntervalMs >= 60_000) { "清理间隔不能小于 60000 毫秒" }
    }
}

data class CleanupResult(
    val deleted: Long,
    val cutoff: Long,
)

/** 计算 UTC 自然日的保留截止时间。 */
fun retentionCutoff(now: Long, retentionDays: Int): Long =
    now - retentionDays * 86_400_000L
