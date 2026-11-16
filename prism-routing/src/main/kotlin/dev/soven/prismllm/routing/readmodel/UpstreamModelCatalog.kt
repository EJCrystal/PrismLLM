package dev.soven.prismllm.routing.readmodel

import dev.soven.prismllm.channel.domain.ChannelKeyRecord
import dev.soven.prismllm.channel.domain.ChannelModelRecord
import dev.soven.prismllm.channel.domain.ChannelRecord
import dev.soven.prismllm.channel.domain.key.ChannelKeyState
import dev.soven.prismllm.protocol.ProtocolConversion
import dev.soven.prismllm.routing.domain.candidate.CandidateSet
import dev.soven.prismllm.routing.domain.candidate.RouteCandidate
import dev.soven.prismllm.routing.domain.candidate.RoutingAccessScope
import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.shared.domain.UpstreamCredential

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

/** 一条被模型路由明确选中的凭据与其公开模型映射。 */
private data class ConfiguredRouteCredential(
    val position: Int,
    val channel: ChannelRecord,
    val credential: UpstreamCredential,
    val mapping: ChannelModelRecord,
    val protocols: List<GatewayProtocol>,
)

/**
 * 按持久化模型路由解析可直连渠道模型。列表顺序是唯一的候选顺序来源。
 *
 * @author 文杰
 * @since 2026-10-22
 */
class UpstreamModelCatalog(
    private val configuration: RoutingConfigurationReader,
) {
    /** 仅曝光已配置路由中仍可用、获准且支持该映射的凭据。 */
    suspend fun listDirectModels(scope: RoutingAccessScope = RoutingAccessScope()): List<DirectModel> {
        val snapshot = configuration.read(null)
        val channels = snapshot.channels.filter { it.enabled && scope.allowsChannel(it.id) && it.protocols.isNotEmpty() }
        val routes = snapshot.routes.filter { it.configured }.associateBy { it.modelId }
        val credentials = credentialIndex(snapshot.channels)
        val now = System.currentTimeMillis()
        return channels.flatMap { channel ->
            channel.modelMappings.mapNotNull { mapping ->
                if (!mapping.catalogActive || !scope.allowsModel(mapping.publicModelId)) return@mapNotNull null
                val protocols = routes[mapping.publicModelId]?.entries.orEmpty().asSequence()
                    .filter { it.channelId == channel.id }
                    .mapNotNull { credentials[it] }
                    .filter { it.isRoutable(now) && it.supports(mapping.upstreamModel) && scope.allowsCredential(channel.id, it) }
                    .flatMap { credentialProtocols(channel, it, snapshot).asSequence() }
                    .toSet()
                if (protocols.isEmpty()) return@mapNotNull null
                DirectModel(
                    publicId = mapping.publicModelId,
                    upstreamModel = mapping.upstreamModel,
                    canonicalModelId = mapping.canonicalModelId,
                    channel = channel.copy(protocols = channel.protocols.filter { it in protocols }),
                )
            }
        }
    }

    suspend fun listEnabledModels(): List<String> = listDirectModels()
        .map { it.publicId }
        .distinct()
        .sorted()

    suspend fun findCandidates(model: String, protocol: GatewayProtocol?): List<RouteCandidate> {
        val snapshot = configuration.read(model)
        val route = snapshot.routes.firstOrNull { it.modelId == model && it.configured } ?: return emptyList()
        return routeCandidates(
            model = model,
            routeCredentials = configuredRouteCredentials(model, route, snapshot),
            protocol = protocol,
            now = System.currentTimeMillis(),
            scope = RoutingAccessScope(),
        )
    }

    /**
     * 解析模型在当前协议下的授权与可用性；候选严格按已保存的路由列表展开。
     *
     * 未配置路由不回退到渠道目录。空路由仍表示模型已配置，但没有可用凭据。
     */
    suspend fun resolve(
        model: String,
        protocol: GatewayProtocol,
        now: Long = System.currentTimeMillis(),
        scope: RoutingAccessScope = RoutingAccessScope(),
    ): CandidateSet {
        val snapshot = configuration.read(model)
        val channels = snapshot.channels
        val route = snapshot.routes.firstOrNull { it.modelId == model && it.configured }
        val routeChannelIds = route?.entries.orEmpty().map { it.channelId }.toSet()
        val knownChannels = if (scope.isUnrestricted) channels.filter { it.enabled || it.id in routeChannelIds } else channels
        val globallyConfigured = configuredModels(model, knownChannels)
        val modelAllowed = scope.allowsModel(model)
        val routeCredentials = route?.let { configuredRouteCredentials(model, it, snapshot) }.orEmpty()
        val modelAccessDenied = globallyConfigured.isNotEmpty() && !modelAllowed
        val scopedEntries = globallyConfigured.filter { entry ->
            modelAllowed && scope.allowsChannel(entry.channel.id)
        }
        val channelAccessDenied = globallyConfigured.isNotEmpty() && scopedEntries.isEmpty()
        val routeAccessDenied = globallyConfigured.isNotEmpty() && route?.entries?.takeIf { it.isNotEmpty() }?.none { identity ->
            scope.allowsCredential(identity.channelId, identity.credentialId, identity.credentialType == CredentialRouteType.OAUTH)
        } == true
        val accessDenied = modelAccessDenied || channelAccessDenied || routeAccessDenied

        val selectedRouteCredentials = routeCredentials.filter { entry ->
            scope.allowsChannel(entry.channel.id) && scope.allowsCredential(entry.channel.id, entry.credential)
        }
        val protocolReachable = when {
            route?.entries.isNullOrEmpty() -> scopedEntries.any { entry ->
                supportsProtocol(protocol, entry.channel.protocols)
            }
            selectedRouteCredentials.isNotEmpty() -> selectedRouteCredentials.any { entry ->
                supportsProtocol(protocol, entry.protocols)
            }
            else -> scopedEntries.isNotEmpty() && !accessDenied
        }
        val candidates = if (route == null || accessDenied) emptyList() else routeCandidates(
            model = model,
            routeCredentials = routeCredentials,
            protocol = protocol,
            now = now,
            scope = scope,
        )

        // 只用本模型列表中的静态密钥计算自动恢复提示。
        val retryAt = routeCredentials.asSequence()
            .filter { !accessDenied && modelAllowed && it.channel.enabled }
            .filter { supportsProtocol(protocol, it.protocols) }
            .filter { scope.allowsCredential(it.channel.id, it.credential) }
            .mapNotNull { it.credential as? ChannelKeyRecord }
            .filter { it.state == ChannelKeyState.AUTO_TEMPORARY }
            .mapNotNull { it.recoveryAt(now) }
            .minOrNull()

        return CandidateSet(
            candidates = candidates,
            configured = scopedEntries.isNotEmpty() && !accessDenied,
            protocolReachable = protocolReachable,
            retryAt = retryAt,
            accessDenied = accessDenied,
            modelAccessDenied = modelAccessDenied,
        )
    }

    private fun routeCandidates(
        model: String,
        routeCredentials: List<ConfiguredRouteCredential>,
        protocol: GatewayProtocol?,
        now: Long,
        scope: RoutingAccessScope,
    ): List<RouteCandidate> = routeCredentials.mapNotNull { entry ->
        if (!entry.channel.enabled || entry.protocols.isEmpty() || !entry.credential.isRoutable(now) ||
            !scope.allowsModel(model) || !scope.allowsCredential(entry.channel.id, entry.credential)
        ) return@mapNotNull null
        if (protocol != null && !supportsProtocol(protocol, entry.protocols)) return@mapNotNull null
        RouteCandidate(
            channel = entry.channel.copy(protocols = entry.protocols),
            credential = entry.credential,
            upstreamModel = entry.mapping.upstreamModel,
            position = entry.position,
        )
    }

    /** 列表身份为外层顺序；该身份支持的上游别名按名称稳定展开且保持相邻。 */
    private fun configuredRouteCredentials(
        model: String,
        route: ModelCredentialRouteSnapshot,
        snapshot: RoutingConfigurationSnapshot,
    ): List<ConfiguredRouteCredential> {
        val channelsById = snapshot.channels.associateBy { it.id }
        val credentials = credentialIndex(snapshot.channels)
        return route.entries.flatMapIndexed { position, identity ->
            val channel = channelsById[identity.channelId] ?: return@flatMapIndexed emptyList()
            val credential = credentials[identity] ?: return@flatMapIndexed emptyList()
            val protocols = credentialProtocols(channel, credential, snapshot)
            channel.modelMappings.asSequence()
                .filter { it.catalogActive && it.publicModelId == model && credential.supports(it.upstreamModel) }
                .sortedBy { it.upstreamModel }
                .map { mapping -> ConfiguredRouteCredential(position, channel, credential, mapping, protocols) }
                .toList()
        }
    }

    private fun credentialProtocols(
        channel: ChannelRecord,
        credential: UpstreamCredential,
        snapshot: RoutingConfigurationSnapshot,
    ): List<GatewayProtocol> = if (credential.isOAuth) {
        val providerProtocols = snapshot.oauthProtocols[credential.id].orEmpty().toSet()
        channel.protocols.filter { it in providerProtocols }
    } else {
        channel.protocols
    }

    private fun supportsProtocol(requested: GatewayProtocol, available: List<GatewayProtocol>): Boolean =
        requested in available || available.any { ProtocolConversion.supports(requested, it) }

    private fun credentialIndex(channels: List<ChannelRecord>): Map<CredentialRouteIdentity, UpstreamCredential> {
        val index = HashMap<CredentialRouteIdentity, UpstreamCredential>()
        channels.forEach { channel ->
            channel.keys.forEach { index[CredentialRouteIdentity(channel.id, CredentialRouteType.STATIC, it.id)] = it }
            channel.oauthAccounts.forEach { index[CredentialRouteIdentity(channel.id, CredentialRouteType.OAUTH, it.id)] = it }
        }
        return index
    }

    /** 对外模型 ID 精确匹配规范 ID；只有 active 映射参与路由。 */
    private fun configuredModels(model: String, channels: List<ChannelRecord>): List<ConfiguredModel> =
        channels.flatMap { channel ->
            channel.modelMappings
                .filter { it.catalogActive && it.publicModelId == model }
                .map { mapping -> ConfiguredModel(channel, mapping) }
        }
}
