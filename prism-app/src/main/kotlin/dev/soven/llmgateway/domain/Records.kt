package dev.soven.llmgateway.domain

import dev.soven.llmgateway.quota.domain.ClientKeyQuotaLimits
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
 * 调用方密钥记录。只保存哈希和展示前缀。
 *
 * @author 文杰
 * @since 2026-10-21
 */
data class ClientKeyRecord(
    val id: String,
    val name: String,
    val keyHash: String,
    val keyPrefix: String,
    val enabled: Boolean,
    val expiresAt: Long?,
    val quotas: ClientKeyQuotaLimits = ClientKeyQuotaLimits(),
    val createdAt: Long,
    val rotatedAt: Long? = null,
    val revokedAt: Long? = null,
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

/** 渠道模型连通性测试的全局默认值。 */
data class ModelTestSettingsRecord(
    val defaultPrompt: String,
    val updatedAt: Long,
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

