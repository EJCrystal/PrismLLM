package dev.soven.llmgateway.proxy

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 单实例内的上游在途请求计数，仅用于管理面实时占用展示。
 *
 * 计数按渠道与渠道密钥聚合，与限流规则无关：未配置并发上限的密钥同样会被统计。
 * 数据只保存在进程内存中，进程退出即清零，不跨实例共享。
 */
class UpstreamOccupancy {
    private val counters = ConcurrentHashMap<Slot, AtomicInteger>()

    fun begin(channelId: String, keyId: String): Slot {
        val slot = Slot(channelId, keyId)
        counters.computeIfAbsent(slot) { AtomicInteger() }.incrementAndGet()
        return slot
    }

    fun end(slot: Slot) {
        counters[slot]?.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }

    /** 在一次上游尝试期间持有占用名额，尝试结束（含取消与异常）后释放。 */
    suspend fun <T> track(channelId: String, keyId: String, block: suspend () -> T): T {
        val slot = begin(channelId, keyId)
        return try {
            block()
        } finally {
            end(slot)
        }
    }

    /** 返回当前在途数大于零的槽位；空闲槽位不进入快照。 */
    fun snapshot(): Map<Slot, Int> = counters.entries
        .mapNotNull { (slot, counter) -> counter.get().takeIf { it > 0 }?.let { slot to it } }
        .toMap()

    data class Slot(val channelId: String, val keyId: String)
}
