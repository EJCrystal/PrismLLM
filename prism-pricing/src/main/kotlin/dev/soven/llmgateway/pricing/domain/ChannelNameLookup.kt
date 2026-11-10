package dev.soven.llmgateway.pricing.domain

/** 模型单价的渠道名称查询端口；计价上下文不直接依赖渠道仓储。 */
fun interface ChannelNameLookup {
    fun names(channelIds: Set<String>): Map<String, String>
}
