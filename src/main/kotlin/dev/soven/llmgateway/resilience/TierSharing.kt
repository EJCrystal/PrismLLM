package dev.soven.llmgateway.resilience

import dev.soven.llmgateway.domain.RouteCandidate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 档位分摊：候选按档位升序使用，同档内轮询分摊流量。
 *
 * 外层按渠道优先级分档，同档渠道轮询；渠道内按密钥优先级分档，同档密钥轮询。
 * 同一渠道内不同上游模型仍保持稳定顺序，作为同模型的主入口/回退。
 *
 * @author 文杰
 * @since 2026-10-25
 */
class TierSharing {
    private val counters = ConcurrentHashMap<String, AtomicLong>()

    /**
     * 按 scope（对外模型 ID）轮转同档候选的起点。首个请求从档内顺序的第一名开始。
     */
    fun order(scope: String, candidates: List<RouteCandidate>): List<RouteCandidate> {
        if (candidates.size <= 1) return candidates
        val offset = counters.computeIfAbsent(scope) { AtomicLong() }.getAndIncrement()
        return candidates
            .groupBy { it.position }
            .toSortedMap()
            .flatMap { (_, tier) -> orderTier(tier, offset) }
    }

    private fun orderTier(tier: List<RouteCandidate>, offset: Long): List<RouteCandidate> {
        val channels = tier.groupBy { it.channel.id }.values.toList()
        return rotateGroups(channels, offset).flatMap { channelCandidates ->
            channelCandidates
                .groupBy { it.upstreamModel }
                .values
                .flatMap { modelCandidates ->
                    modelCandidates
                        .groupBy { it.key.priority }
                        .toSortedMap()
                        .flatMap { (_, keyTier) ->
                            val keys = keyTier.groupBy { it.key.id }.values.toList()
                            rotateGroups(keys, offset).flatten()
                        }
                }
        }
    }

    private fun rotateGroups(groups: List<List<RouteCandidate>>, offset: Long): List<List<RouteCandidate>> {
        if (groups.size <= 1) return groups
        val start = (offset % groups.size).toInt()
        return List(groups.size) { index -> groups[(start + index) % groups.size] }
    }
}
