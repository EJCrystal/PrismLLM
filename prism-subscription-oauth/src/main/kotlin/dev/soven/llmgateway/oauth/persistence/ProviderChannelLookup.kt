package dev.soven.llmgateway.oauth.persistence

/**
 * 识别哪些渠道属于系统 provider 渠道。
 *
 * 托管标记决定账号是否受 provider 渠道约束；该判断需要读取渠道上下文，
 * 由应用层注入实现。调用发生在账号仓储的事务内，实现直接复用当前事务。
 */
fun interface ProviderChannelLookup {
    fun managedChannelIds(channelIds: Collection<String>): Set<String>
}