package dev.soven.prismllm.api

import dev.soven.prismllm.repo.ModelRouteCredentialMetadata
import dev.soven.prismllm.routing.readmodel.CredentialRouteIdentity
import dev.soven.prismllm.routing.readmodel.CredentialRouteType
import dev.soven.prismllm.shared.error.badRequest
import kotlinx.serialization.Serializable

@Serializable
data class ModelCredentialRouteIdentityDto(
    val channelId: String,
    val credentialType: String,
    val credentialId: String,
)

/** A full replacement; expectedRevision is required, including for first-time configuration. */
@Serializable
data class SaveModelCredentialRouteRequest(
    val modelId: String,
    val expectedRevision: Long,
    val entries: List<ModelCredentialRouteIdentityDto>,
)

@Serializable
data class ModelCredentialRouteCredentialResponse(
    val channelId: String,
    val credentialType: String,
    val credentialId: String,
    val channelName: String,
    val credentialName: String,
    val upstreamModels: List<String>,
    val protocols: List<String>,
    val selectable: Boolean,
    val reason: String? = null,
)

@Serializable
data class ModelCredentialRouteDetailResponse(
    val modelId: String,
    val revision: Long,
    val configured: Boolean,
    val entries: List<ModelCredentialRouteCredentialResponse>,
    val availableCredentials: List<ModelCredentialRouteCredentialResponse>,
)

@Serializable
data class ModelCredentialRouteListItem(
    val modelId: String,
    val configured: Boolean,
    val entryCount: Int,
    val availableCount: Int,
    val revision: Long,
)

@Serializable
data class ModelCredentialRouteListResponse(
    val items: List<ModelCredentialRouteListItem>,
    val total: Int,
    val offset: Int,
    val limit: Int,
)

/** 调用方访问范围模型选项：只暴露路由身份与可选择性，不包含凭据名称或明文。 */
@Serializable
data class ModelRouteAccessEntryResponse(
    val channelId: String,
    val credentialType: String,
    val credentialId: String,
    val selectable: Boolean,
    val reason: String? = null,
)

@Serializable
data class ModelRouteAccessOptionResponse(
    val modelId: String,
    val revision: Long,
    val entries: List<ModelRouteAccessEntryResponse>,
)

@Serializable
data class ModelRouteAccessOptionsResponse(val models: List<ModelRouteAccessOptionResponse>)

internal fun ModelRouteCredentialMetadata.toResponse() = ModelCredentialRouteCredentialResponse(
    channelId = identity.channelId,
    credentialType = identity.credentialType.value,
    credentialId = identity.credentialId,
    channelName = channelName,
    credentialName = credentialName,
    upstreamModels = upstreamModels,
    protocols = protocols,
    selectable = selectable,
    reason = reason,
)

internal fun validateModelRouteModelId(modelId: String): String {
    if (modelId.length !in 1..200 || modelId.isBlank() || modelId != modelId.trim() || modelId.any(Char::isISOControl)) {
        throw badRequest("invalid_model_route", "对外模型 ID 必须为 1..200 个字符，且不能包含首尾空白或控制字符")
    }
    return modelId
}

internal fun SaveModelCredentialRouteRequest.identities(): List<CredentialRouteIdentity> {
    validateModelRouteModelId(modelId)
    if (expectedRevision < 0 || expectedRevision == Long.MAX_VALUE) {
        throw badRequest("invalid_model_route", "模型路由版本无效")
    }
    if (entries.size > 10_000) throw badRequest("invalid_model_route", "模型凭据条目不能超过 10000 条")
    val identities = entries.map { entry ->
        if (listOf(entry.channelId, entry.credentialId).any {
                it.length !in 1..36 || it.isBlank() || it != it.trim() || it.any(Char::isISOControl)
            }) {
            throw badRequest("invalid_model_route", "渠道与凭据 ID 无效")
        }
        val type = CredentialRouteType.fromValue(entry.credentialType)
            ?: throw badRequest("invalid_model_route", "凭据类型必须为 static 或 oauth")
        CredentialRouteIdentity(entry.channelId, type, entry.credentialId)
    }
    if (identities.distinct().size != identities.size) {
        throw badRequest("invalid_model_route", "不能重复选择同一渠道中的同类凭据")
    }
    return identities
}
