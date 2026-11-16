package dev.soven.prismllm.routing.domain.candidate

import dev.soven.prismllm.channel.domain.ChannelRecord
import dev.soven.prismllm.shared.domain.UpstreamCredential

/**
 * 解析后的路由候选，只包含数据面需要的字段。
 *
 * 路由上下文不反向引用订阅账号等其它上下文的实现类型；调用方需要账号或被自动禁用的
 * 静态密钥时，在自己的模块里对 [credential] 做类型投影。
 *
 * @author 文杰
 * @since 2026-10-21
 */
data class RouteCandidate(
    val channel: ChannelRecord,
    val credential: UpstreamCredential,
    val upstreamModel: String,
    /** 在该模型持久化凭据列表中的位置；同一凭据的上游别名共享位置。 */
    val position: Int,
) {
    /** 计费使用的模型 ID：优先渠道配置的规范 ID，未配置时回退上游模型。 */
    val pricingModelId: String
        get() = channel.modelMappings
            .firstOrNull { it.upstreamModel.trim() == upstreamModel.trim() }
            ?.canonicalModelId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: upstreamModel.trim()
}
