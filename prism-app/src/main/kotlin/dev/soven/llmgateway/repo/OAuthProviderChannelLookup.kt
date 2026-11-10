package dev.soven.llmgateway.repo

import dev.soven.llmgateway.channel.domain.ChannelKind
import dev.soven.llmgateway.channel.persistence.Channels
import dev.soven.llmgateway.oauth.persistence.ProviderChannelLookup
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select

/** 在账号仓储的事务内识别系统 provider 渠道，供托管标记使用。 */
class OAuthProviderChannelLookup : ProviderChannelLookup {
    override fun managedChannelIds(channelIds: Collection<String>): Set<String> {
        val ids = channelIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptySet()
        return Channels.select(Channels.id)
            .where { (Channels.id inList ids) and (Channels.kind eq ChannelKind.PROVIDER.value) }
            .map { it[Channels.id] }
            .toSet()
    }
}
