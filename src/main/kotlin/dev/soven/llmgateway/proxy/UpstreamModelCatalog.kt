package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.domain.RouteCandidate
import dev.soven.llmgateway.repo.ChannelRepository
import dev.soven.llmgateway.repo.OAuthAccountRepository

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
    private val oauthAccountRepository: OAuthAccountRepository,
) {
    suspend fun listDirectModels(): List<DirectModel> =
        directExposureChannels().flatMap { channel ->
            channel.modelMappings.filter { mapping -> channel.credentialsFor(mapping.upstreamModel).isNotEmpty() }.map { mapping ->
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
                    .flatMap { mapping ->
                        channel.credentialsFor(mapping.upstreamModel).map { credential ->
                            RouteCandidate(
                                channel = channel,
                                credential = credential,
                                upstreamModel = mapping.upstreamModel,
                                position = channel.priority,
                            )
                        }
                    }
            }
            .sortedWith(compareBy(
                { it.position },
                { it.channel.name },
                { it.credential.priority },
                { it.credential.name },
                { it.credential.id },
                { it.upstreamModel },
            ))

    private suspend fun directExposureChannels(): List<ChannelRecord> {
        val accountsByChannel = oauthAccountRepository.listByChannel()
        return channelRepository.list()
            .filter { it.enabled }
            .map { channel -> channel.copy(oauthAccounts = accountsByChannel[channel.id].orEmpty()) }
    }
}
