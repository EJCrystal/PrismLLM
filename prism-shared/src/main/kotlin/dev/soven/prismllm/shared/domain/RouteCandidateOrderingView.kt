package dev.soven.prismllm.shared.domain

/**
 * 排序器需要的最小候选视图：只包含档位与稳定排序用的标量，不携带渠道配置或凭据内容。
 *
 * 路由候选直接实现该视图，排序器返回原候选类型，无需在下沉到 resilience 后再映射回完整候选。
 * 「原生协议优先」依赖入口协议，仍由 routing 在调用排序器前完成分组。
 *
 * @author 文杰
 * @since 2026-11-09
 */
interface RouteCandidateOrderingView {
    val channelId: String
    val channelName: String

    /** 渠道优先级档位，数值越小越优先。 */
    val channelPriority: Int

    val credentialId: String
    val credentialName: String

    /** 同一渠道内的凭据优先级档位，数值越小越优先。 */
    val credentialPriority: Int

    val upstreamModel: String
}