package dev.soven.prismllm.resilience

import dev.soven.prismllm.shared.domain.RouteCandidateOrderingView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 档位分摊：凭据优先级优先，渠道优先级次之；同档内轮询分摊流量。
 *
 * 同一模型和协议组内先按凭据优先级分档，再按渠道优先级分档；两档相同的候选先轮询渠道，
 * 再轮询渠道内凭据。
 * 同一凭据支持多个上游模型名时保持稳定顺序，首个名称作为主入口、其余作为回退。
 * 若提供了 affinityKey（如客户端 prompt_cache_key），在两档相同的候选内使用哈希亲和路由，
 * 保证相同 key 的请求稳定命中相同渠道与凭据，利用上游 Prompt Cache，同时不污染轮询计数。
 *
 * @author 文杰
 * @since 2026-10-25
 */
class TierSharing {
    private val channelCounters = ConcurrentHashMap<String, AtomicLong>()
    private val credentialCounters = ConcurrentHashMap<String, AtomicLong>()

    private companion object {
        /** 亲和 key 来自客户端，超长输入会在每个渠道与密钥上重复哈希，截断以限制 CPU 放大。 */
        const val MAX_AFFINITY_KEY_LENGTH = 512
    }

    /**
     * 按 scope（对外模型 ID）隔离优先级档位及轮询计数。首个请求从各档的第一名开始。
     * 当提供 affinityKey 时，使用最高随机权重哈希（HRW/Rendezvous Hashing）独立计算同档亲和分数，
     * 避免因无关渠道进入冷却改变剩余可用渠道的亲和目标，保持缓存亲和稳定。
     */
    fun <T : RouteCandidateOrderingView> order(
        scope: String,
        candidates: List<T>,
        affinityKey: String? = null,
    ): List<T> {
        if (candidates.size <= 1) return candidates
        val affinity = affinityKey?.take(MAX_AFFINITY_KEY_LENGTH)
        return candidates
            .groupBy { it.credentialPriority }
            .toSortedMap()
            .flatMap { (credentialPriority, credentialTier) ->
                credentialTier
                    .groupBy { it.channelPriority }
                    .toSortedMap()
                    .flatMap { (channelPriority, channelTier) ->
                        val tierScope = "$scope|credential=$credentialPriority|channel=$channelPriority"
                        orderTier(tierScope, channelTier, affinity)
                    }
            }
    }

    private fun <T : RouteCandidateOrderingView> orderTier(
        tierScope: String,
        tier: List<T>,
        affinityKey: String?,
    ): List<T> {
        val channelOffset = if (affinityKey != null) {
            0L
        } else {
            channelCounters.computeIfAbsent(tierScope) { AtomicLong() }.getAndIncrement()
        }
        val channels = tier.groupBy { it.channelId }.values.toList()
        val orderedChannels = if (affinityKey != null) {
            channels.sortedByDescending { group ->
                val channelId = group.first().channelId
                affinityOffset("$affinityKey#$tierScope#channel#$channelId")
            }
        } else {
            rotateGroups(channels, channelOffset)
        }
        return orderedChannels.flatMapIndexed { channelIndex, channelCandidates ->
            val channelId = channelCandidates.first().channelId
            val counterKey = "$tierScope|$channelId"
            val credentialOffset = if (affinityKey != null) {
                0L
            } else if (channelIndex == 0) {
                // 只推进当前首选渠道的凭据计数，避免渠道轮转与凭据轮转同步造成组合偏置。
                credentialCounters.computeIfAbsent(counterKey) { AtomicLong() }.getAndIncrement()
            } else {
                credentialCounters[counterKey]?.get() ?: 0L
            }
            // 同档凭据轮询或基于 HRW 的独立亲和排序；同一凭据的多个上游模型名保持候选原有稳定顺序。
            val credentials = channelCandidates.groupBy { it.credentialId }.values.toList()
            val orderedCredentials = if (affinityKey != null) {
                credentials.sortedByDescending { credentialGroup ->
                    val credentialId = credentialGroup.first().credentialId
                    affinityOffset("$affinityKey#$counterKey#credential#$credentialId")
                }
            } else {
                rotateGroups(credentials, credentialOffset)
            }
            orderedCredentials.flatten()
        }
    }

    private fun <T> rotateGroups(groups: List<List<T>>, offset: Long): List<List<T>> {
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
