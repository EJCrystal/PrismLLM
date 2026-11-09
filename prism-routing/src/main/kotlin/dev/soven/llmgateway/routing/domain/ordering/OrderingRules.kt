package dev.soven.llmgateway.routing.domain.ordering

import dev.soven.llmgateway.routing.domain.candidate.RouteCandidate
import dev.soven.llmgateway.shared.domain.GatewayProtocol

/**
 * 候选的稳定初始顺序与原生协议优先规则。
 *
 * 初始顺序是后续档位分摊、账号调度和冷却过滤的输入；同一份目录快照必须得到相同顺序，
 * 否则轮询在请求之间会漂移。原生协议优先于协议转换：转换多一层损耗，能用原生就别转。
 *
 * @author 文杰
 * @since 2026-11-09
 */
object OrderingRules {
    private val initialComparator = compareBy<RouteCandidate>(
        { it.position },
        { it.channel.name },
        { it.credential.priority },
        { it.credential.name },
        { it.credential.id },
        { it.upstreamModel },
    )

    /** 按渠道档位、渠道名、凭据档位、凭据名、凭据 ID 和上游模型做稳定排序。 */
    fun initialOrder(candidates: List<RouteCandidate>): List<RouteCandidate> =
        candidates.sortedWith(initialComparator)

    /** 原生协议候选整体排在需要协议转换的候选之前，两组内部保持原有顺序。 */
    fun nativeProtocolFirst(candidates: List<RouteCandidate>, protocol: GatewayProtocol): List<RouteCandidate> =
        candidates.filter { protocol in it.channel.protocols } +
            candidates.filter { protocol !in it.channel.protocols }
}