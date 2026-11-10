package dev.soven.prismllm.repo

import dev.soven.prismllm.channel.persistence.ChannelKeys
import dev.soven.prismllm.channel.persistence.Channels
import dev.soven.prismllm.quota.persistence.ClientKeys
import dev.soven.prismllm.requestlog.persistence.RequestLogNameKind
import dev.soven.prismllm.requestlog.persistence.RequestLogNameResolver
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select

/** 在请求日志仓储的事务内查询渠道、密钥与调用方密钥的当前名称。 */
class RequestLogCurrentNames : RequestLogNameResolver {
    override fun resolve(kind: RequestLogNameKind, ids: Set<String>): Map<String, String> {
        val (idColumn, nameColumn) = columns(kind)
        val distinctIds = ids.asSequence().filter { it.isNotBlank() }.distinct().toList()
        if (distinctIds.isEmpty()) return emptyMap()
        return idColumn.table.select(idColumn, nameColumn)
            .where { idColumn inList distinctIds }
            .mapNotNull { row ->
                val id = row[idColumn].takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = row[nameColumn].takeIf { it.isNotBlank() } ?: return@mapNotNull null
                id to name
            }
            .toMap()
    }

    private fun columns(kind: RequestLogNameKind): Pair<Column<String>, Column<String>> = when (kind) {
        RequestLogNameKind.CLIENT_KEY -> ClientKeys.id to ClientKeys.name
        RequestLogNameKind.CHANNEL -> Channels.id to Channels.name
        RequestLogNameKind.CHANNEL_KEY -> ChannelKeys.id to ChannelKeys.name
    }
}