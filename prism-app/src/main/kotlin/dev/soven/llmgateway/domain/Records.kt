package dev.soven.llmgateway.domain

import kotlinx.serialization.json.JsonObject

/**
 * 上游 /models 返回的模型。
 *
 * @author 文杰
 * @since 2026-10-22
 */
data class UpstreamModel(
    val id: String,
    val ownedBy: String? = null,
)

/**
 * 一条管理面写操作审计记录。字段只包含请求元数据。
 */
data class AdminAuditRecord(
    val id: Long,
    val actor: String?,
    val action: String,
    val method: String,
    val path: String,
    val statusCode: Int,
    val result: String,
    val requestId: String,
    val sourceIp: String?,
    val errorCode: String?,
    val createdAt: Long,
    val details: JsonObject? = null,
)

/**
 * 管理员账号记录。
 *
 * @author 文杰
 * @since 2026-10-21
 */
data class AdminUserRecord(
    val id: String,
    val username: String,
    val passwordHash: String,
    val avatarDataUrl: String? = null,
)

