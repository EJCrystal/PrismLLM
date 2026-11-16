package dev.soven.prismllm.repo

import dev.soven.prismllm.channel.domain.ProviderEndpoints
import dev.soven.prismllm.channel.persistence.ChannelRepository
import dev.soven.prismllm.oauth.persistence.OAuthAccountRepository
import dev.soven.prismllm.routing.readmodel.RoutingConfigurationReader
import dev.soven.prismllm.routing.readmodel.RoutingConfigurationSnapshot
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.inTopLevelSuspendTransaction
import java.sql.Connection

/** Reads all routing credentials and routes from one PostgreSQL repeatable-read snapshot. */
class RoutingConfigurationSnapshotRepository(
    private val database: Database,
    private val channels: ChannelRepository,
    private val oauthAccounts: OAuthAccountRepository,
    private val modelRoutes: ModelCredentialRouteRepository,
) : RoutingConfigurationReader {
    override suspend fun read(modelId: String?): RoutingConfigurationSnapshot =
        inTopLevelSuspendTransaction(
            db = database,
            transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ,
            readOnly = true,
        ) {
            val channelRecords = channels.listInTransaction()
            val accountsByChannel = oauthAccounts.listByChannelInTransaction()
            val hydratedChannels = channelRecords.map { channel ->
                channel.copy(oauthAccounts = accountsByChannel[channel.id].orEmpty())
            }
            val oauthProtocols = accountsByChannel.values.asSequence().flatten()
                .associate { it.id to ProviderEndpoints.of(it.provider).protocols }
            val routeRecords = if (modelId == null) {
                modelRoutes.listInTransaction()
            } else {
                listOfNotNull(modelRoutes.findInTransaction(modelId))
            }
            RoutingConfigurationSnapshot(hydratedChannels, routeRecords, oauthProtocols)
        }
}
