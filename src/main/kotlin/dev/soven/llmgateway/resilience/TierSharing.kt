package dev.soven.llmgateway.resilience

import dev.soven.llmgateway.domain.RouteCandidate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 档位分摊：候选按档位升序使用，同档内轮询分摊流量。
 *
 * 外层按渠道优先级分档，同档渠道轮询；渠道内先按密钥优先级分档，同档密钥轮询。
 * 同一密钥支持多个上游模型名时保持稳定顺序，首个名称作为主入口、其余作为回退。
 * 若提供了 affinityKey（如客户端 prompt_cache_key），在同档内使用哈希亲和路由，
 * 保证相同 key 的请求稳定命中相同渠道与密钥，利用上游 Prompt Cache，同时不污染轮询计数。
 *
 * @author 文杰
 * @since 2026-10-25
 */
class TierSharing {
    private val channelCounters = ConcurrentHashMap<String, AtomicLong>()
    private val keyCounters = ConcurrentHashMap<String, AtomicLong>()

    /**
     * 按 scope（对外模型 ID）轮转同档候选的起点。首个请求从档内顺序的第一名开始。
     * 当提供 affinityKey 时，使用最高随机权重哈希（HRW/Rendezvous Hashing）独立计算亲和分数，
     * 避免因无关渠道进入冷却改变剩余可用渠道的亲和目标，保持缓存亲和稳定。
     */
    fun order(scope: String, candidates: List<RouteCandidate>, affinityKey: String? = null): List<RouteCandidate> {
        if (candidates.size <= 1) return candidates
        val channelOffset = if (affinityKey != null) {
            0L
        } else {
            channelCounters.computeIfAbsent(scope) { AtomicLong() }.getAndIncrement()
        }
        return candidates
            .groupBy { it.position }
            .toSortedMap()
            .flatMap { (_, tier) -> orderTier(scope, tier, channelOffset, affinityKey) }
    }

    private fun orderTier(
        scope: String,
        tier: List<RouteCandidate>,
        channelOffset: Long,
        affinityKey: String?,
    ): List<RouteCandidate> {
        val channels = tier.groupBy { it.channel.id }.values.toList()
        val orderedChannels = if (affinityKey != null) {
            channels.sortedByDescending { group ->
                val channelId = group.first().channel.id
                affinityOffset("$affinityKey#$scope#channel#$channelId")
            }
        } else {
            rotateGroups(channels, channelOffset)
        }
        return orderedChannels.flatMapIndexed { channelIndex, channelCandidates ->
            val channelId = channelCandidates.first().channel.id
            val counterKey = "$scope|$channelId"
            val keyOffset = if (affinityKey != null) {
                0L
            } else if (channelIndex == 0) {
                // 只推进当前首选渠道的密钥计数，避免渠道轮转与密钥轮转同步造成组合偏置。
                keyCounters.computeIfAbsent(counterKey) { AtomicLong() }.getAndIncrement()
            } else {
                keyCounters[counterKey]?.get() ?: 0L
            }
            channelCandidates
                .groupBy { it.key.priority }
                .toSortedMap()
                .flatMap { (_, keyTier) ->
                    // 同档密钥轮询或基于 HRW 的独立亲和排序；同一密钥内的多个上游模型名保持候选原有稳定顺序。
                    val keys = keyTier.groupBy { it.key.id }.values.toList()
                    val orderedKeys = if (affinityKey != null) {
                        keys.sortedByDescending { keyGroup ->
                            val keyId = keyGroup.first().key.id
                            affinityOffset("$affinityKey#$counterKey#key#$keyId")
                        }
                    } else {
                        rotateGroups(keys, keyOffset)
                    }
                    orderedKeys.flatten()
                }
        }
    }

    private fun rotateGroups(groups: List<List<RouteCandidate>>, offset: Long): List<List<RouteCandidate>> {
        if (groups.size <= 1) return groups
        val start = Math.floorMod(offset, groups.size.toLong()).toInt()
        return List(groups.size) { index -> groups[(start + index) % groups.size] }
    }

    private fun affinityOffset(key: String): Long {
        var hash = -3750763034362895579L
        for (i in 0 until key.length) {
            hash = hash xor key[i].code.toLong()
            hash *= 1099511628211L
        }
        // SplitMix64 finalizer 提供强雪崩与均匀伪随机分布，避免哈希偏置
        hash = hash xor (hash ushr 30)
        hash *= -4658895280553007687L
        hash = hash xor (hash ushr 27)
        hash *= -7723592293117605633L
        hash = hash xor (hash ushr 31)
        return hash and Long.MAX_VALUE
    }
}
