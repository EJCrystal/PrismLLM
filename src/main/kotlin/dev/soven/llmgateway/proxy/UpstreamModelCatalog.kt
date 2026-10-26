package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.domain.RouteCandidate
import dev.soven.llmgateway.repo.ChannelRepository

/**
 * 一个允许直连的渠道模型，以及它对外使用的规范 ID。
 */
data class DirectModel(
    val publicId: String,
    val upstreamModel: String,
    val canonicalModelId: String?,
    val channel: ChannelRecord,
)

/**
 * 允许直连的启用渠道上游模型目录。用于未建别名时的 `/v1/models` 展示和直连路由。
 *
 * @author 文杰
 * @since 2026-10-22
 */
class UpstreamModelCatalog(
    private val channelRepository: ChannelRepository,
) {
    suspend fun listDirectModels(): List<DirectModel> =
        directExposureChannels().flatMap { channel ->
            channel.modelMappings.map { mapping ->
                DirectModel(
                    publicId = mapping.canonicalModelId ?: mapping.upstreamModel,
                    upstreamModel = mapping.upstreamModel,
                    canonicalModelId = mapping.canonicalModelId,
                    channel = channel,
                )
            }
        }

    suspend fun listEnabledModels(): List<String> = listDirectModels()
        .map { it.publicId }
        .distinct()
        .sorted()

    suspend fun findCandidates(model: String, protocol: GatewayProtocol?): List<RouteCandidate> =
        directExposureChannels()
            .filter { protocol == null || protocol in it.protocols }
            .flatMap { channel ->
                channel.modelMappings
                    .filter { (it.canonicalModelId ?: it.upstreamModel) == model }
                    .map { mapping ->
                        RouteCandidate(channel = channel, upstreamModel = mapping.upstreamModel, position = channel.priority)
                    }
            }
            .sortedWith(compareBy({ it.position }, { it.channel.name }, { it.upstreamModel }))

    private suspend fun directExposureChannels(): List<ChannelRecord> =
        channelRepository.list().filter { it.enabled }
}
