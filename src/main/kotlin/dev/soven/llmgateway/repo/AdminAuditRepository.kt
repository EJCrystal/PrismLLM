package dev.soven.llmgateway.repo

import dev.soven.llmgateway.db.AdminAuditLogs
import dev.soven.llmgateway.domain.AdminAuditRecord
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 管理面写操作审计持久化。调用方只能追加和读取，不提供修改或删除接口。
 */
class AdminAuditRepository(private val database: Database) {
    suspend fun record(
        actor: String?,
        action: String,
        method: String,
        path: String,
        statusCode: Int,
        requestId: String,
        sourceIp: String?,
        errorCode: String?,
        details: JsonObject? = null,
    ): AdminAuditRecord = suspendTransaction(database) {
        recordInTransaction(actor, action, method, path, statusCode, requestId, sourceIp, errorCode, details)
    }

    /** 与系统触发的密钥状态变更共用当前事务，审计写入失败时一并回滚。 */
    internal fun recordInTransaction(
        actor: String?,
        action: String,
        method: String,
        path: String,
        statusCode: Int,
        requestId: String,
        sourceIp: String?,
        errorCode: String?,
        details: JsonObject? = null,
    ): AdminAuditRecord {
        val createdAt = System.currentTimeMillis()
        val result = if (statusCode < 400) "success" else "failure"
        val id = AdminAuditLogs.insertReturning(listOf(AdminAuditLogs.id)) {
            it[AdminAuditLogs.actor] = actor
            it[AdminAuditLogs.action] = action
            it[AdminAuditLogs.method] = method
            it[AdminAuditLogs.path] = path
            it[AdminAuditLogs.statusCode] = statusCode
            it[AdminAuditLogs.result] = result
            it[AdminAuditLogs.requestId] = requestId
            it[AdminAuditLogs.sourceIp] = sourceIp
            it[AdminAuditLogs.errorCode] = errorCode
            it[AdminAuditLogs.details] = details?.toString()
            it[AdminAuditLogs.createdAt] = createdAt
        }.single()[AdminAuditLogs.id]
        return AdminAuditRecord(
            id = id,
            actor = actor,
            action = action,
            method = method,
            path = path,
            statusCode = statusCode,
            result = result,
            requestId = requestId,
            sourceIp = sourceIp,
            errorCode = errorCode,
            createdAt = createdAt,
            details = details,
        )
    }

    suspend fun list(limit: Int, beforeId: Long? = null): List<AdminAuditRecord> =
        suspendTransaction(database) {
            val query = AdminAuditLogs.selectAll()
            val filtered = if (beforeId != null) {
                query.where { AdminAuditLogs.id less beforeId }
            } else {
                query
            }
            filtered.orderBy(AdminAuditLogs.id, SortOrder.DESC)
                .limit(limit.coerceIn(1, 500))
                .map(::toRecord)
        }

    private fun toRecord(row: ResultRow) = AdminAuditRecord(
        id = row[AdminAuditLogs.id],
        actor = row[AdminAuditLogs.actor],
        action = row[AdminAuditLogs.action],
        method = row[AdminAuditLogs.method],
        path = row[AdminAuditLogs.path],
        statusCode = row[AdminAuditLogs.statusCode],
        result = row[AdminAuditLogs.result],
        requestId = row[AdminAuditLogs.requestId],
        sourceIp = row[AdminAuditLogs.sourceIp],
        errorCode = row[AdminAuditLogs.errorCode],
        details = row[AdminAuditLogs.details]?.let { Json.parseToJsonElement(it).jsonObject },
        createdAt = row[AdminAuditLogs.createdAt],
    )
}
