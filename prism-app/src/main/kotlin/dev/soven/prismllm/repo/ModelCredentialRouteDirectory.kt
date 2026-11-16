package dev.soven.prismllm.repo

import dev.soven.prismllm.channel.domain.ProviderEndpoints
import dev.soven.prismllm.channel.domain.key.ChannelKeyState
import dev.soven.prismllm.channel.persistence.ChannelKeyModels
import dev.soven.prismllm.channel.persistence.ChannelKeys
import dev.soven.prismllm.channel.persistence.ChannelModels
import dev.soven.prismllm.channel.persistence.Channels
import dev.soven.prismllm.oauth.domain.OAuthAccountModelCatalogEntry
import dev.soven.prismllm.oauth.domain.OAuthAuthState
import dev.soven.prismllm.oauth.persistence.OAuthAccountChannels
import dev.soven.prismllm.oauth.persistence.OAuthAccountModels
import dev.soven.prismllm.oauth.persistence.OAuthAccounts
import dev.soven.prismllm.routing.readmodel.CredentialRouteIdentity
import dev.soven.prismllm.routing.readmodel.CredentialRouteType
import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.shared.domain.OAuthProvider
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/** Only public configuration metadata; credential/token columns are never read. */
internal data class ModelRouteCredentialMetadata(
    val identity: CredentialRouteIdentity,
    val channelName: String,
    val credentialName: String,
    val upstreamModels: List<String>,
    val protocols: List<String>,
    val selectable: Boolean,
    val reason: String?,
)

internal data class ModelRouteChannel(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val protocols: List<GatewayProtocol>,
    val mappings: Map<String, List<String>>,
)

internal data class ModelRouteCredential(
    val identity: CredentialRouteIdentity,
    val name: String,
    val active: Boolean,
    val models: Set<String>?,
    val catalog: Set<String>? = null,
    val protocols: Set<GatewayProtocol>? = null,
) {
    fun supports(model: String): Boolean = (models == null || model in models) && (catalog == null || model in catalog)
}

internal class ModelCredentialRouteDirectorySnapshot(
    private val channels: Map<String, ModelRouteChannel>,
    private val credentials: Map<CredentialRouteIdentity, ModelRouteCredential>,
) {
    val modelIds: Set<String> = channels.values.filter { it.enabled }.flatMap { it.mappings.keys }.toSet()

    fun available(modelId: String): List<ModelRouteCredentialMetadata> = credentials.keys
        .map { metadata(modelId, it) }
        .filter { it.upstreamModels.isNotEmpty() }
        .sortedWith(compareBy({ it.channelName }, { it.credentialName }, { it.identity.credentialType.value }, { it.identity.credentialId }))

    fun metadata(modelId: String, identity: CredentialRouteIdentity): ModelRouteCredentialMetadata {
        val channel = channels[identity.channelId]
        val credential = credentials[identity]
        val upstreamModels = channel?.mappings?.get(modelId).orEmpty()
            .filter { credential?.supports(it) == true }.distinct().sorted()
        val protocols = channel?.protocols.orEmpty()
            .filter { protocol ->
                val providerProtocols = credential?.protocols
                providerProtocols == null || protocol in providerProtocols
            }
            .map { it.value }
        val reason = when {
            channel == null -> "channel_missing"
            credential == null -> "credential_missing"
            !channel.enabled -> "channel_disabled"
            !credential.active -> "credential_disabled"
            upstreamModels.isEmpty() -> "model_unsupported"
            protocols.isEmpty() -> "protocol_unsupported"
            else -> null
        }
        return ModelRouteCredentialMetadata(
            identity = identity,
            channelName = channel?.name ?: identity.channelId,
            credentialName = credential?.name ?: identity.credentialId,
            upstreamModels = upstreamModels,
            protocols = protocols,
            selectable = reason == null,
            reason = reason,
        )
    }
}

/** Cross-context metadata projection owned by the application, not the routing or OAuth modules. */
internal class ModelCredentialRouteDirectory(private val database: Database) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): ModelCredentialRouteDirectorySnapshot = suspendTransaction(database) { loadInTransaction() }

    /** Called by save validation in the route transaction; lock parent channels before credential rows. */
    fun loadInTransaction(lock: Collection<CredentialRouteIdentity> = emptyList()): ModelCredentialRouteDirectorySnapshot {
        check(TransactionManager.current().db === database) { "model route directory transaction database mismatch" }
        if (lock.isNotEmpty()) {
            Channels.select(Channels.id).where { Channels.id inList lock.map { it.channelId }.distinct().sorted() }
                .orderBy(Channels.id).forUpdate().toList()
            ChannelKeys.select(ChannelKeys.id).where {
                ChannelKeys.id inList lock.filter { it.credentialType == CredentialRouteType.STATIC }.map { it.credentialId }.sorted()
            }.orderBy(ChannelKeys.id).forUpdate().toList()
            OAuthAccounts.select(OAuthAccounts.id).where {
                OAuthAccounts.id inList lock.filter { it.credentialType == CredentialRouteType.OAUTH }.map { it.credentialId }.sorted()
            }.orderBy(OAuthAccounts.id).forUpdate().toList()
        }
        val mappings = ChannelModels.select(
            ChannelModels.channelId, ChannelModels.upstreamModel, ChannelModels.canonicalModelId, ChannelModels.catalogActive,
        ).filter { it[ChannelModels.catalogActive] }.groupBy { it[ChannelModels.channelId] }
        val channels = Channels.select(Channels.id, Channels.name, Channels.enabled, Channels.protocols).associate { row ->
            val id = row[Channels.id]
            id to ModelRouteChannel(
                id = id,
                name = row[Channels.name],
                enabled = row[Channels.enabled],
                protocols = row[Channels.protocols].split(',').mapNotNull { GatewayProtocol.fromValue(it.trim()) }.distinct(),
                mappings = mappings[id].orEmpty().groupBy(
                    { it[ChannelModels.canonicalModelId] ?: it[ChannelModels.upstreamModel] },
                    { it[ChannelModels.upstreamModel] },
                ),
            )
        }
        val keyModels = ChannelKeyModels.selectAll().groupBy { it[ChannelKeyModels.keyId] }
        val credentials = ChannelKeys.select(ChannelKeys.id, ChannelKeys.channelId, ChannelKeys.name, ChannelKeys.state)
            .map { row ->
                val id = row[ChannelKeys.id]
                ModelRouteCredential(
                    identity = CredentialRouteIdentity(row[ChannelKeys.channelId], CredentialRouteType.STATIC, id),
                    name = row[ChannelKeys.name],
                    active = row[ChannelKeys.state] == ChannelKeyState.ACTIVE.value,
                    models = keyModels[id].orEmpty().map { it[ChannelKeyModels.upstreamModel] }.toSet(),
                )
            }.toMutableList()
        val links = OAuthAccountChannels.select(OAuthAccountChannels.channelId, OAuthAccountChannels.accountId)
            .groupBy { it[OAuthAccountChannels.accountId] }
        val accountModels = OAuthAccountModels.selectAll().groupBy { it[OAuthAccountModels.accountId] }
        OAuthAccounts.select(
            OAuthAccounts.id, OAuthAccounts.name, OAuthAccounts.enabled, OAuthAccounts.authState,
            OAuthAccounts.provider, OAuthAccounts.modelCatalog,
        ).forEach { row ->
            val id = row[OAuthAccounts.id]
            val provider = OAuthProvider.fromValue(row[OAuthAccounts.provider])
            val catalog = row[OAuthAccounts.modelCatalog]?.let { raw ->
                runCatching { json.decodeFromString<List<OAuthAccountModelCatalogEntry>>(raw).map { it.upstreamModel }.toSet() }
                    .getOrElse { emptySet() }
            }
            links[id].orEmpty().forEach { link ->
                credentials += ModelRouteCredential(
                    identity = CredentialRouteIdentity(link[OAuthAccountChannels.channelId], CredentialRouteType.OAUTH, id),
                    name = row[OAuthAccounts.name],
                    active = row[OAuthAccounts.enabled] && row[OAuthAccounts.authState] == OAuthAuthState.ACTIVE.value,
                    models = accountModels[id]?.map { it[OAuthAccountModels.upstreamModel] }?.toSet(),
                    catalog = catalog,
                    protocols = provider?.let { ProviderEndpoints.of(it).protocols.toSet() } ?: emptySet(),
                )
            }
        }
        return ModelCredentialRouteDirectorySnapshot(channels, credentials.associateBy { it.identity })
    }
}
