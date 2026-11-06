package dev.soven.llmgateway.oauth

import dev.soven.llmgateway.domain.ChannelKeyRecord
import dev.soven.llmgateway.domain.OAuthRoutingStrategy
import dev.soven.llmgateway.domain.RouteCandidate
import java.util.concurrent.ConcurrentHashMap

/**
 * 订阅账号的候选排序。只在同一渠道、同一密钥优先级内重排 OAuth 账号，
 * 静态密钥和不同优先级档位保持原有顺序；启用 prompt cache 亲和时完全让位给亲和路由。
 *
 * 策略：
 * - `round_robin`：保留 [dev.soven.llmgateway.resilience.TierSharing] 的轮询结果。
 * - `drain`：固定一个账号直到它额度用尽或进入冷却，再顺延到下一个；恢复后也不回抢。
 * - `least_used`：按额度读数中已用比例最低者优先，没有读数的账号排在已知账号之后。
 * - `pace`：按剩余比例摊到距离重置的小时数，余量最高者优先，优先使用即将过期的额度。
 *
 * @author 文杰
 * @since 2026-11-06
 */
class OAuthAccountRouting(
    private val strategy: OAuthRoutingStrategy,
    private val hints: suspend (Collection<ChannelKeyRecord>, Long) -> Map<String, OAuthRoutingHint> = { _, _ -> emptyMap() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** 每个渠道与密钥档位当前正在耗尽的账号。 */
    private val drained = ConcurrentHashMap<String, String>()

    /**
     * 对已按档位与轮询排好序的候选做账号级重排。[affinityApplied] 为 true 时保持原顺序，
     * 避免打散客户端通过 prompt_cache_key 建立的缓存亲和。
     */
    suspend fun order(candidates: List<RouteCandidate>, affinityApplied: Boolean): List<RouteCandidate> {
        if (affinityApplied || strategy == OAuthRoutingStrategy.ROUND_ROBIN || candidates.size < 2) {
            return candidates
        }
        val oauthKeys = candidates.map { it.key }.filter { it.oauthPolicy != null }.distinctBy { it.id }
        if (oauthKeys.size < 2) return candidates
        val nowMs = now()
        val readings = if (strategy == OAuthRoutingStrategy.DRAIN) {
            emptyMap()
        } else {
            hints(oauthKeys, nowMs)
        }

        val result = ArrayList<RouteCandidate>(candidates.size)
        var start = 0
        while (start < candidates.size) {
            val first = candidates[start]
            var end = start + 1
            while (end < candidates.size &&
                candidates[end].channel.id == first.channel.id &&
                candidates[end].key.priority == first.key.priority
            ) {
                end++
            }
            result += orderRun(first.channel.id, candidates.subList(start, end), readings, nowMs)
            start = end
        }
        return result
    }

    private fun orderRun(
        channelId: String,
        run: List<RouteCandidate>,
        readings: Map<String, OAuthRoutingHint>,
        nowMs: Long,
    ): List<RouteCandidate> {
        // 同一密钥的多个上游模型名保持连续；按首次出现顺序分组。
        val groups = LinkedHashMap<String, MutableList<RouteCandidate>>()
        for (candidate in run) groups.getOrPut(candidate.key.id) { mutableListOf() }.add(candidate)
        val keyGroups = groups.values.toList()
        val slots = keyGroups.indices.filter { keyGroups[it].first().key.oauthPolicy != null }
        if (slots.isEmpty()) return run
        if (slots.size < 2) {
            // 只剩一个可用账号时也推进 drain 指针，避免它在其他账号恢复后回抢。
            if (strategy == OAuthRoutingStrategy.DRAIN) {
                val only = keyGroups[slots.single()].first().key
                drained[poolKey(channelId, only)] = only.id
            }
            return run
        }

        val ranked = when (strategy) {
            OAuthRoutingStrategy.ROUND_ROBIN -> return run
            OAuthRoutingStrategy.DRAIN -> drainOrder(
                poolKey = poolKey(channelId, keyGroups[slots.first()].first().key),
                keyGroups = keyGroups,
                slots = slots,
            )
            OAuthRoutingStrategy.LEAST_USED -> rank(
                keyGroups = keyGroups,
                slots = slots,
                score = { key -> usedShare(key, readings[key.id]) },
                highestFirst = false,
            )
            OAuthRoutingStrategy.PACE -> rank(
                keyGroups = keyGroups,
                slots = slots,
                score = { key -> pace(key, readings[key.id], nowMs) },
                highestFirst = true,
            )
        }
        val reordered = ArrayList<List<RouteCandidate>>(keyGroups.size)
        reordered.addAll(keyGroups)
        slots.forEachIndexed { index, slot -> reordered[slot] = ranked[index] }
        return reordered.flatten()
    }

    private fun drainOrder(
        poolKey: String,
        keyGroups: List<List<RouteCandidate>>,
        slots: List<Int>,
    ): List<List<RouteCandidate>> {
        val current = drained[poolKey]
        val currentSlot = current?.let { keyId ->
            slots.firstOrNull { keyGroups[it].first().key.id == keyId }
        }
        if (currentSlot != null) {
            return listOf(keyGroups[currentSlot]) + slots.filter { it != currentSlot }.map { keyGroups[it] }
        }
        val next = slots.first()
        drained[poolKey] = keyGroups[next].first().key.id
        return slots.map { keyGroups[it] }
    }

    private fun rank(
        keyGroups: List<List<RouteCandidate>>,
        slots: List<Int>,
        score: (ChannelKeyRecord) -> Double?,
        highestFirst: Boolean,
    ): List<List<RouteCandidate>> {
        // 无读数的账号排在已知账号之后；同分保持原有轮询顺序。
        val sorted = slots.sortedWith(
            compareBy<Int> { score(keyGroups[it].first().key) == null }
                .thenBy { slot ->
                    val value = score(keyGroups[slot].first().key) ?: 0.0
                    if (highestFirst) -value else value
                },
        )
        return sorted.map { keyGroups[it] }
    }

    /** 最近一次额度读数中已用比例的最高值；没有读数时退回本地窗口用量。 */
    private fun usedShare(key: ChannelKeyRecord, hint: OAuthRoutingHint?): Double? {
        val shares = listOfNotNull(hint?.quota?.primary?.usedPercent, hint?.quota?.secondary?.usedPercent)
        if (shares.isNotEmpty()) return shares.max()
        val limit = key.oauthPolicy?.windowLimit?.takeIf { it > 0 } ?: return null
        return (hint?.windowUsed ?: 0).toDouble() / limit * 100
    }

    /**
     * 剩余比例除以距离重置的小时数。上游额度优先取窗口跨度最长的一档（通常为每周窗口）；
     * 没有额度读数时用本地滑动窗口估算。
     */
    private fun pace(key: ChannelKeyRecord, hint: OAuthRoutingHint?, nowMs: Long): Double? {
        val quota = hint?.quota
        val window = listOfNotNull(quota?.primary, quota?.secondary)
            .maxByOrNull { it.windowSeconds ?: 0 }
        if (window != null) {
            val hours = window.resetsAt
                ?.let { ((it - nowMs) / 3_600_000.0).coerceAtLeast(MIN_PACE_HOURS) }
            return if (hours != null) window.remainingPercent / hours else window.remainingPercent
        }
        val policy = key.oauthPolicy ?: return null
        val limit = policy.windowLimit?.takeIf { it > 0 } ?: return null
        val remaining = (limit - (hint?.windowUsed ?: 0)).coerceAtLeast(0).toDouble() / limit * 100
        val hours = hint?.windowOldestAt
            ?.let { ((it + policy.windowSeconds * 1_000L - nowMs) / 3_600_000.0).coerceAtLeast(MIN_PACE_HOURS) }
            ?: (policy.windowSeconds / 3_600.0)
        return remaining / hours
    }

    private fun poolKey(channelId: String, key: ChannelKeyRecord): String = "$channelId#${key.priority}"

    private companion object {
        /** 距重置不足 15 分钟时按 15 分钟计，避免除零并避免余量被无限放大。 */
        const val MIN_PACE_HOURS = 0.25
    }
}
