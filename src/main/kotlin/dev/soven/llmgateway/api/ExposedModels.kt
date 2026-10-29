package dev.soven.llmgateway.api

import dev.soven.llmgateway.domain.GatewayProtocol

/**
 * `/v1/models` 对外暴露的一个模型及其来源。
 *
 * @author 文杰
 * @since 2026-10-23
 */
data class ExposedModel(
    val id: String,
    val source: ExposedModelSource,
    val canonicalModelId: String? = null,
    val upstreamModels: List<String> = emptyList(),
    val channelNames: List<String> = emptyList(),
)

enum class ExposedModelSource(val value: String) {
    ALIAS("alias"),
    CANONICAL("canonical"),
    UPSTREAM("upstream"),
}

/**
 * 数据面与管理面共用的对外模型聚合逻辑，保证管理页展示结果与 `/v1/models` 一致。
 */
suspend fun listExposedModels(app: GatewayApplication, protocol: GatewayProtocol? = null): List<ExposedModel> {
    val aliases = app.aliasRepository.listAvailableAliases(protocol)
    val aliasNames = app.aliasRepository.listAllAliasNames()
    val directModels = app.modelCatalog.listDirectModels()
        .filter { protocol == null || protocol in it.channel.protocols }
        .filterNot { it.publicId in aliasNames }
        .groupBy { it.publicId }
        .map { (publicId, entries) ->
            ExposedModel(
                id = publicId,
                source = if (entries.any { it.canonicalModelId != null }) {
                    ExposedModelSource.CANONICAL
                } else {
                    ExposedModelSource.UPSTREAM
                },
                canonicalModelId = entries.firstNotNullOfOrNull { it.canonicalModelId },
                upstreamModels = entries.map { it.upstreamModel }.distinct().sorted(),
                channelNames = entries.map { it.channel.name }.distinct().sorted(),
            )
        }
    return (aliases.map { ExposedModel(it, ExposedModelSource.ALIAS) } + directModels)
        .distinctBy { it.id }
        .sortedBy { it.id }
}
