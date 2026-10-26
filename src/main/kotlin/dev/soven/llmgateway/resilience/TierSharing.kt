package dev.soven.llmgateway.resilience

import dev.soven.llmgateway.domain.RouteCandidate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 档位分摊：候选按档位升序使用，同档内轮询分摊流量。
 *
 * 输入候选须已按（档位, 稳定次序）排好；轮询只改变同档内的起点，不改变档间顺序。
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
            .flatMap { (_, tier) -> rotate(tier, offset) }
    }

    private fun rotate(tier: List<RouteCandidate>, offset: Long): List<RouteCandidate> {
        if (tier.size <= 1) return tier
        val channels = tier.groupBy { it.channel.id }.values.toList()
        if (channels.size <= 1) return tier
        val start = (offset % channels.size).toInt()
        return List(channels.size) { index -> channels[(start + index) % channels.size] }.flatten()
    }
}
