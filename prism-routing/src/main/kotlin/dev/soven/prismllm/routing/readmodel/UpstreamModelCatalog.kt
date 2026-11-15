package dev.soven.prismllm.routing.readmodel

import dev.soven.prismllm.channel.domain.ChannelModelRecord
import dev.soven.prismllm.channel.domain.ChannelRecord
import dev.soven.prismllm.channel.domain.key.ChannelKeyState
import dev.soven.prismllm.channel.persistence.ChannelRepository
import dev.soven.prismllm.protocol.ProtocolConversion
import dev.soven.prismllm.routing.domain.candidate.CandidateSet
import dev.soven.prismllm.routing.domain.candidate.RouteCandidate
import dev.soven.prismllm.routing.domain.candidate.RoutingAccessScope
import dev.soven.prismllm.routing.domain.ordering.OrderingRules
import dev.soven.prismllm.shared.domain.GatewayProtocol

/**
 * 一个允许直连的渠道模型，以及它对外使用的规范 ID。
 */
data class DirectModel(
    val publicId: String,
    val upstreamModel: String,
    val canonicalModelId: String?,
    val channel: ChannelRecord,
)

/** 配置快照里的一条模型映射，授权与可用性分别判断。 */
private data class ConfiguredModel(val channel: ChannelRecord, val mapping: ChannelModelRecord)

/**
 * 允许直连的启用渠道上游模型目录。用于未建别名时的 `/v1/models` 展示和直连路由。
 *
 * @author 文杰
 * @since 2026-10-22
 */
class UpstreamModelCatalog(
    private val channelRepository: ChannelRepository,
    private val oauthAccounts: OAuthAccountDirectory,
) {
    /** 列出授权范围内具有当前可路由凭据的启用渠道模型，保留原生协议供调用方筛选。 */
    suspend fun listDirectModels(scope: RoutingAccessScope = RoutingAccessScope()): List<DirectModel> =
        directExposureChannels().filter { scope.allowsChannel(it.id) }.flatMap { channel ->
            channel.modelMappings.filter { mapping ->
                scope.allowsModel(mapping.publicModelId) && channel.credentialsFor(mapping.upstreamModel)
                    .any { scope.allowsCredential(channel.id, it) }
            }.map { mapping ->
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
            .let(OrderingRules::initialOrder)

    /**
     * 解析模型在当前协议下的授权与可用性，所有诊断及候选基于一次完整配置读取。
     *
     * 不限制范围继续只识别启用渠道。受限范围先以包含停用对象的配置判断授权，再计算
     * 协议与当前可路由凭据；不存在模型保留原有不存在语义，只有已配置模型才分类授权拒绝。
     * 范围外渠道、密钥不会参与候选或恢复时间提示。
     *
     * @param model 请求中的对外模型 ID。
     * @param protocol 调用方协议，可使用授权渠道支持的协议转换。
     * @param now 本次凭据可用性与恢复诊断共用的时间点。
     * @param scope 本次请求固定的访问范围快照。
     */
    suspend fun resolve(
        model: String,
        protocol: GatewayProtocol,
        now: Long = System.currentTimeMillis(),
        scope: RoutingAccessScope = RoutingAccessScope(),
    ): CandidateSet {
        val channels = configurationChannels()
        val globallyConfigured = configuredModels(model, if (scope.isUnrestricted) channels.filter { it.enabled } else channels)
        val scopedEntries = globallyConfigured.filter { scope.allowsModel(model) && scope.allowsChannel(it.channel.id) }
        val supportingByEntry = scopedEntries.associateWith { entry ->
            (entry.channel.keys + entry.channel.oauthAccounts).filter { it.supports(entry.mapping.upstreamModel) }
        }
        // 以授权渠道交集中的全部支持凭据判断排除，空凭据映射不能掩盖全排除的权限拒绝。
        val allSupportingDenied = !scope.deniedChannelKeys.isNullOrEmpty() &&
            supportingByEntry.values.any { it.isNotEmpty() } && supportingByEntry.none { (entry, credentials) ->
                credentials.any { scope.allowsCredential(entry.channel.id, it) }
            }
        val configured = if (allSupportingDenied) emptyList() else scopedEntries.filter { entry ->
            val supportingCredentials = supportingByEntry.getValue(entry)
            when {
                scope.allowedChannelKeys != null -> supportingCredentials.any { scope.allowsCredential(entry.channel.id, it) }
                scope.deniedChannelKeys.isNullOrEmpty() -> true
                // 黑名单只能禁止已配置的凭据；没有支持凭据仍是不可用，而非权限拒绝。
                else -> supportingCredentials.isEmpty() || supportingCredentials.any { scope.allowsCredential(entry.channel.id, it) }
            }
        }
        val modelAccessDenied = globallyConfigured.isNotEmpty() && !scope.allowsModel(model)
        val accessDenied = globallyConfigured.isNotEmpty() && configured.isEmpty()
        val protocolEntries = configured.filter { entry ->
            protocol in entry.channel.protocols ||
                entry.channel.protocols.any { ProtocolConversion.supports(protocol, it) }
        }
        val allCandidates = protocolEntries.filter { it.channel.enabled }.flatMap { entry ->
            entry.channel.credentialsFor(entry.mapping.upstreamModel, now)
                .filter { scope.allowsCredential(entry.channel.id, it) }.map { credential ->
                    RouteCandidate(
                        channel = entry.channel,
                        credential = credential,
                        upstreamModel = entry.mapping.upstreamModel,
                        position = entry.channel.priority,
                    )
                }
        }.let(OrderingRules::initialOrder)
        val candidates = OrderingRules.nativeProtocolFirst(allCandidates, protocol)
        // 临时停用到期的密钥会自行恢复，用最早的恢复时间告诉调用方何时重试；
        // 人工或永久停用不会自动恢复，不参与重试提示。
        val retryAt = protocolEntries.asSequence().filter { it.channel.enabled }
            .flatMap { (channel, mapping) ->
                channel.keys.asSequence().filter { key ->
                    key.state == ChannelKeyState.AUTO_TEMPORARY && key.supports(mapping.upstreamModel) &&
                        scope.allowsCredential(channel.id, key)
                }
            }
            .mapNotNull { key -> key.recoveryAt(now) }
            .minOrNull()
        return CandidateSet(
            candidates = candidates,
            configured = configured.isNotEmpty(),
            protocolReachable = protocolEntries.isNotEmpty(),
            retryAt = retryAt,
            accessDenied = accessDenied,
            modelAccessDenied = modelAccessDenied,
        )
    }

    /**
     * 对外模型 ID 到渠道映射的唯一匹配入口。
     *
     * 路由、曝光与「不可用」诊断共用这一份规则，避免某个入口认得的 ID 在另一个入口变成
     * 「模型不存在」。匹配口径为 [ChannelModelRecord.publicModelId] 精确相等。
     */
    private suspend fun configuredModels(model: String): List<ConfiguredModel> =
        configuredModels(model, directExposureChannels())

    /** 在给定快照内按对外模型 ID 精确匹配映射，不重复读取配置。 */
    private fun configuredModels(model: String, channels: List<ChannelRecord>): List<ConfiguredModel> =
        channels.flatMap { channel ->
            channel.modelMappings
                .filter { it.publicModelId == model }
                .map { mapping -> ConfiguredModel(channel, mapping) }
        }

    /** 原有目录入口只曝光启用渠道，保留不限制调用方的行为。 */
    private suspend fun directExposureChannels(): List<ChannelRecord> = configurationChannels().filter { it.enabled }

    /** 读取包含停用渠道、静态密钥与订阅凭据的完整配置，供一次解析共用。 */
    private suspend fun configurationChannels(): List<ChannelRecord> {
        val accountsByChannel = oauthAccounts.listByChannel()
        return channelRepository.list()
            .map { channel -> channel.copy(oauthAccounts = accountsByChannel[channel.id].orEmpty()) }
    }
}
