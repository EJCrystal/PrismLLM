package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.domain.ChannelModelRecord
import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.shared.domain.GatewayProtocol
import dev.soven.llmgateway.domain.RouteCandidate
import dev.soven.llmgateway.protocol.ProtocolConversion
import dev.soven.llmgateway.repo.ChannelRepository
import dev.soven.llmgateway.repo.OAuthAccountRepository
import dev.soven.llmgateway.resilience.ChannelKeyState

/**
 * 一个允许直连的渠道模型，以及它对外使用的规范 ID。
 */
data class DirectModel(
    val publicId: String,
    val upstreamModel: String,
    val canonicalModelId: String?,
    val channel: ChannelRecord,
)

/** 启用渠道里的一条模型映射，凭据可用性由调用方按需判断。 */
private data class ConfiguredModel(val channel: ChannelRecord, val mapping: ChannelModelRecord)

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
                    publicId = mapping.publicModelId,
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
        configuredModels(model)
            .filter { protocol == null || protocol in it.channel.protocols }
            .flatMap { entry ->
                entry.channel.credentialsFor(entry.mapping.upstreamModel).map { credential ->
                    RouteCandidate(
                        channel = entry.channel,
                        credential = credential,
                        upstreamModel = entry.mapping.upstreamModel,
                        position = entry.channel.priority,
                    )
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

    /**
     * 一次目录快照下解析模型的可用性，路由与「为什么不可用」的判定都基于同一份快照，
     * 避免候选查询与诊断查询之间凭据恢复（临时停用到期、人工启用）导致自相矛盾的结论。
     *
     * @param candidates        当前协议下可用的路由候选（含协议转换）。
     * @param configured        全协议下模型已配置的渠道条目，与凭据是否可路由无关。
     * @param protocolReachable 是否存在任一渠道能原生支持或转换出当前协议。
     * @param retryAt           凭据全部不可路由时的最早自动恢复时间；没有临时停用密钥时为 null。
     */
    data class ModelAvailability(
        val candidates: List<RouteCandidate>,
        val configured: Boolean,
        val protocolReachable: Boolean,
        val retryAt: Long?,
    )

    /**
     * 解析模型在当前协议下的可用性。所有分支都基于 [configuredModels] 的单次快照。
     */
    suspend fun resolve(model: String, protocol: GatewayProtocol, now: Long = System.currentTimeMillis()): ModelAvailability {
        val configured = configuredModels(model)
        val protocolEntries = configured.filter { entry ->
            protocol in entry.channel.protocols ||
                entry.channel.protocols.any { ProtocolConversion.supports(protocol, it) }
        }
        val allCandidates = protocolEntries.flatMap { entry ->
            entry.channel.credentialsFor(entry.mapping.upstreamModel, now).map { credential ->
                RouteCandidate(
                    channel = entry.channel,
                    credential = credential,
                    upstreamModel = entry.mapping.upstreamModel,
                    position = entry.channel.priority,
                )
            }
        }.sortedWith(compareBy(
            { it.position },
            { it.channel.name },
            { it.credential.priority },
            { it.credential.name },
            { it.credential.id },
            { it.upstreamModel },
        ))
        // 原生协议优先于协议转换：转换多一层损耗，能用原生就别转。
        val candidates = allCandidates.filter { protocol in it.channel.protocols } +
            allCandidates.filter { protocol !in it.channel.protocols }
        // 临时停用到期的密钥会自行恢复，用最早的恢复时间告诉调用方何时重试；
        // 人工或永久停用不会自动恢复，不参与重试提示。
        val retryAt = protocolEntries.asSequence()
            .flatMap { (channel, mapping) ->
                channel.keys.asSequence().filter { key ->
                    key.state == ChannelKeyState.AUTO_TEMPORARY && key.supports(mapping.upstreamModel)
                }
            }
            .mapNotNull { key -> key.recoveryAt(now) }
            .minOrNull()
        return ModelAvailability(
            candidates = candidates,
            configured = configured.isNotEmpty(),
            protocolReachable = protocolEntries.isNotEmpty(),
            retryAt = retryAt,
        )
    }

    /**
     * 对外模型 ID 到渠道映射的唯一匹配入口。
     *
     * 路由、曝光与「不可用」诊断共用这一份规则，避免某个入口认得的 ID 在另一个入口变成
     * 「模型不存在」。匹配口径为 [ChannelModelRecord.publicModelId] 精确相等。
     */
    private suspend fun configuredModels(model: String): List<ConfiguredModel> =
        directExposureChannels().flatMap { channel ->
            channel.modelMappings
                .filter { it.publicModelId == model }
                .map { mapping -> ConfiguredModel(channel, mapping) }
        }

    private suspend fun directExposureChannels(): List<ChannelRecord> {
        val accountsByChannel = oauthAccountRepository.listByChannel()
        return channelRepository.list()
            .filter { it.enabled }
            .map { channel -> channel.copy(oauthAccounts = accountsByChannel[channel.id].orEmpty()) }
    }
}
