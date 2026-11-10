package dev.soven.llmgateway.repo

import dev.soven.llmgateway.channel.persistence.Channels
import dev.soven.llmgateway.pricing.domain.ChannelNameLookup
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select

/** 在计价仓储的事务内查询渠道当前名称。 */
class PricingChannelNames : ChannelNameLookup {
    override fun names(channelIds: Set<String>): Map<String, String> {
        val ids = channelIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return Channels.select(Channels.id, Channels.name)
            .where { Channels.id inList ids }
            .associate { it[Channels.id] to it[Channels.name] }
    }
}
