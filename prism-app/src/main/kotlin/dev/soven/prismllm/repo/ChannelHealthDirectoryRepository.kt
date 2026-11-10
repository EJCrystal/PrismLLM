package dev.soven.prismllm.repo

import dev.soven.prismllm.channel.domain.key.ChannelKeyState
import dev.soven.prismllm.channel.persistence.ChannelKeyModels
import dev.soven.prismllm.channel.persistence.ChannelKeys
import dev.soven.prismllm.channel.persistence.ChannelModels
import dev.soven.prismllm.channel.persistence.Channels
import dev.soven.prismllm.oauth.persistence.OAuthAccountChannels
import dev.soven.prismllm.oauth.persistence.OAuthAccountModels
import dev.soven.prismllm.oauth.persistence.OAuthAccounts
import dev.soven.prismllm.requestlog.domain.ChannelHealthDirectory
import dev.soven.prismllm.requestlog.domain.HealthChannelKey
import dev.soven.prismllm.requestlog.domain.HealthChannelOverview
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * 渠道与订阅账号目录的健康度只读实现。
 *
 * 概览与账号名称需要跨渠道、账号上下文读取，因此留在应用层，
 * 由请求日志模块通过 [ChannelHealthDirectory] 端口调用。
 */
class ChannelHealthDirectoryRepository(private val database: Database) : ChannelHealthDirectory {
    /**
     * 与渠道仓储的 `listHealthChannels` 一样跳过完整渠道解析，额外带上密钥支持的模型供冷却概览使用，
     * 并按 [ChannelKeyState.isRoutableAt] 标出每个密钥当前是否参与路由。
     */
    override suspend fun overviews(now: Long): List<HealthChannelOverview> =
        suspendTransaction(database) {
            val rows = Channels.select(Channels.id, Channels.enabled).toList()
            val channelIds = rows.map { it[Channels.id] }
            if (channelIds.isEmpty()) return@suspendTransaction emptyList()
            // 只读健康概览需要的字段，不解析密钥额度/限流等无关 JSON。
            val keyRows = ChannelKeys
                .select(
                    ChannelKeys.id,
                    ChannelKeys.channelId,
                    ChannelKeys.state,
                    ChannelKeys.autoDisableUntil,
                    ChannelKeys.probeUntil,
                )
                .where { ChannelKeys.channelId inList channelIds }
                .toList()
            val modelsByKey = if (keyRows.isEmpty()) {
                emptyMap()
            } else {
                ChannelKeyModels
                    .select(ChannelKeyModels.keyId, ChannelKeyModels.upstreamModel)
                    .where { ChannelKeyModels.keyId inList keyRows.map { it[ChannelKeys.id] } }
                    .groupBy { it[ChannelKeyModels.keyId] }
                    .mapValues { (_, models) -> models.map { it[ChannelKeyModels.upstreamModel] }.sorted() }
            }
            // 渠道模型目录：订阅账号没有显式模型时继承这里，有显式模型时必须与它相交。
            val modelsByChannel = ChannelModels
                .select(ChannelModels.channelId, ChannelModels.upstreamModel)
                .where { ChannelModels.channelId inList channelIds }
                .groupBy { it[ChannelModels.channelId] }
                .mapValues { (_, models) -> models.map { it[ChannelModels.upstreamModel] }.toSet() }
            val keysByChannel = keyRows.groupBy { it[ChannelKeys.channelId] }.mapValues { (channelId, keys) ->
                val channelModels = modelsByChannel[channelId].orEmpty()
                keys.map { key ->
                    val models = modelsByKey[key[ChannelKeys.id]].orEmpty()
                    // 与详情页 routableCredentialCount 同口径：密钥必须支持渠道目录中的模型，
                    // 只有历史授权、渠道目录已移除这些模型时它接不到任何请求。
                    val authorized = models.any { it in channelModels }
                    val routable = authorized && ChannelKeyState.fromValue(key[ChannelKeys.state])
                        ?.isRoutableAt(key[ChannelKeys.autoDisableUntil], key[ChannelKeys.probeUntil], now) == true
                    HealthChannelKey(key[ChannelKeys.id], models, routable)
                }
            }
            // 订阅账号同样参与候选路由。账号可路由只取决于 enabled，
            // 等待重新授权的账号仍保留为候选，不能据此判定渠道不可用。
            val routableAccountsByChannel = routableAccountCounts(channelIds, modelsByChannel)
            rows.map { row ->
                val keys = keysByChannel[row[Channels.id]].orEmpty()
                HealthChannelOverview(
                    id = row[Channels.id],
                    enabled = row[Channels.enabled],
                    keys = keys,
                    routableCredentials = keys.count { it.routable } +
                        (routableAccountsByChannel[row[Channels.id]] ?: 0),
                )
            }
        }

    override suspend fun oauthAccountNames(channelId: String): Map<String, String> =
        suspendTransaction(database) {
            OAuthAccountChannels
                .join(OAuthAccounts, JoinType.INNER, OAuthAccountChannels.accountId, OAuthAccounts.id)
                .selectAll()
                .where { OAuthAccountChannels.channelId eq channelId }
                .associate { it[OAuthAccounts.id] to it[OAuthAccounts.name] }
        }

    /**
     * 每渠道可路由的订阅账号数。账号 `models` 为空表示继承渠道全部模型；
     * 显式授权时必须与渠道目录有交集，否则该账号调不到任何模型。
     */
    private fun JdbcTransaction.routableAccountCounts(
        channelIds: List<String>,
        modelsByChannel: Map<String, Set<String>>,
    ): Map<String, Int> {
        val links = OAuthAccountChannels
            .join(OAuthAccounts, JoinType.INNER, OAuthAccountChannels.accountId, OAuthAccounts.id)
            .select(OAuthAccountChannels.channelId, OAuthAccountChannels.accountId)
            .where { (OAuthAccountChannels.channelId inList channelIds) and (OAuthAccounts.enabled eq true) }
            .toList()
        if (links.isEmpty()) return emptyMap()
        val accountIds = links.map { it[OAuthAccountChannels.accountId] }.distinct()
        val explicitModels = OAuthAccountModels
            .select(OAuthAccountModels.accountId, OAuthAccountModels.upstreamModel)
            .where { OAuthAccountModels.accountId inList accountIds }
            .groupBy { it[OAuthAccountModels.accountId] }
            .mapValues { (_, rows) -> rows.map { it[OAuthAccountModels.upstreamModel] }.toSet() }
        return links.groupBy { it[OAuthAccountChannels.channelId] }.mapValues { (channelId, channelLinks) ->
            val channelModels = modelsByChannel[channelId].orEmpty()
            channelLinks.count { link ->
                val models = explicitModels[link[OAuthAccountChannels.accountId]]
                if (models.isNullOrEmpty()) channelModels.isNotEmpty() else models.any { it in channelModels }
            }
        }
    }
}