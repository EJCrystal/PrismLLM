package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.domain.ChannelModelRecord
import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.domain.RouteCandidate
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

/**
 * 模型已在启用渠道中配置、却没有任何可路由凭据时返回的诊断信息。
 *
 * @param retryAt 最早自动恢复时间；没有任何临时停用的密钥即将到期时为 null。
 */
data class UnavailableModel(val retryAt: Long? = null)

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
     * 判断模型是否已配置在启用渠道中，但当前没有任何可路由凭据。
     *
     * 与 [findCandidates] 的区别只在凭据过滤：这里保留「有映射、无凭据」的模型，
     * 让代理接口区分「模型不存在」（404）与「模型存在但暂时不可用」（503 + Retry-After）。
     *
     * 返回 null 表示模型没有配置在任何启用渠道里，或至少还有一条可路由凭据。
     * [UnavailableModel.retryAt] 取所有临时停用密钥里最早的到期时间，供调用方给出重试提示。
     */
    suspend fun findUnavailableModel(model: String, now: Long = System.currentTimeMillis()): UnavailableModel? {
        val configured = configuredModels(model)
        if (configured.isEmpty()) return null
        if (configured.any { it.channel.credentialsFor(it.mapping.upstreamModel, now).isNotEmpty() }) return null
        // 临时停用到期的密钥会自行恢复，用最早的到期时间告诉调用方何时重试；
        // 人工或永久停用不会自动恢复，不产生重试提示。
        val retryAt = configured.asSequence()
            .flatMap { (channel, mapping) ->
                channel.keys.asSequence().filter { key ->
                    key.state == ChannelKeyState.AUTO_TEMPORARY && key.supports(mapping.upstreamModel)
                }
            }
            .mapNotNull { key -> key.autoDisableUntil?.takeIf { it > now } }
            .minOrNull()
        return UnavailableModel(retryAt)
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
