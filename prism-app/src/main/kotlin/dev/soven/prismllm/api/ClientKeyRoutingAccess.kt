package dev.soven.prismllm.api

import dev.soven.prismllm.quota.domain.ClientKeyAccessPolicy
import dev.soven.prismllm.routing.domain.candidate.RoutingAccessScope
import dev.soven.prismllm.routing.domain.candidate.RoutingAllowedChannelKey

/**
 * 将调用方密钥的访问范围快照转为路由上下文参数，保留 `null` 与空白名单的区别。
 * 集合复制后供本次请求及所有重试共用，路由模块无需依赖配额类型。
 */
fun ClientKeyAccessPolicy.toRoutingAccessScope(): RoutingAccessScope = RoutingAccessScope(
    allowedChannelIds = allowedChannelIds?.toSet(),
    allowedChannelKeys = allowedChannelKeys?.map { RoutingAllowedChannelKey(it.channelId, it.keyId) }?.toSet(),
    allowedModelIds = allowedModelIds?.toSet(),
)
