package dev.soven.llmgateway.requestlog.persistence

import org.jetbrains.exposed.v1.core.Table

/**
 * 请求日志保留策略的运行期设置。数据库中的值覆盖启动环境变量。
 */
object RetentionSettings : Table("retention_settings") {
    val id = integer("id")
    val cleanupEnabled = bool("cleanup_enabled")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}