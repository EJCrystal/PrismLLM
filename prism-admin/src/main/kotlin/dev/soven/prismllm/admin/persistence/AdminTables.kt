package dev.soven.prismllm.admin.persistence

import org.jetbrains.exposed.v1.core.Table

/**
 * 管理员账号表。
 *
 * @author 文杰
 * @since 2026-10-21
 */
object AdminUsers : Table("admin_users") {
    val id = varchar("id", 36)
    val username = varchar("username", 100).uniqueIndex()
    val passwordHash = text("password_hash")
    val avatarDataUrl = text("avatar_data_url").nullable()
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * 管理面写操作审计表。只保存请求元数据，不保存请求正文或任何凭据。
 */
object AdminAuditLogs : Table("admin_audit_logs") {
    val id = long("id").autoIncrement()
    val actor = varchar("actor", 100).nullable()
    val action = varchar("action", 80)
    val method = varchar("method", 10)
    val path = varchar("path", 500)
    val statusCode = integer("status_code")
    val result = varchar("result", 16)
    val requestId = varchar("request_id", 64)
    val sourceIp = varchar("source_ip", 100).nullable()
    val errorCode = varchar("error_code", 100).nullable()
    val details = text("details").nullable()
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
    init {
        index(false, createdAt)
        index(false, actor, createdAt)
    }
}