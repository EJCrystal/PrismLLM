package dev.soven.llmgateway.routing.domain.candidate

import dev.soven.llmgateway.channel.domain.ChannelRecord
import dev.soven.llmgateway.shared.domain.UpstreamCredential
import dev.soven.llmgateway.shared.domain.RouteCandidateOrderingView

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
    override val upstreamModel: String,
    /** 选路档位：直连候选取渠道优先级，数值越小越优先。 */
    val position: Int,
) : RouteCandidateOrderingView {
    override val channelId: String get() = channel.id
    override val channelName: String get() = channel.name
    override val channelPriority: Int get() = position
    override val credentialId: String get() = credential.id
    override val credentialName: String get() = credential.name
    override val credentialPriority: Int get() = credential.priority

    /** 计费使用的模型 ID：优先渠道配置的规范 ID，未配置时回退上游模型。 */
    val pricingModelId: String
        get() = channel.modelMappings
            .firstOrNull { it.upstreamModel.trim() == upstreamModel.trim() }
            ?.canonicalModelId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: upstreamModel.trim()
}
