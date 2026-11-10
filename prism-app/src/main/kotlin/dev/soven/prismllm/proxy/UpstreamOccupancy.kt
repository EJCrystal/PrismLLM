package dev.soven.prismllm.proxy

import java.util.concurrent.ConcurrentHashMap

/**
 * 单实例内的上游在途请求计数，仅用于管理面实时占用展示。
 *
 * 计数按渠道与渠道密钥聚合，与限流规则无关：未配置并发上限的密钥同样会被统计。
 * 数据只保存在进程内存中，进程退出即清零，不跨实例共享。
 */
class UpstreamOccupancy {
    private val counters = ConcurrentHashMap<Slot, Int>()

    fun begin(channelId: String, keyId: String): Slot {
        val slot = Slot(channelId, keyId)
        // compute 对单个槽位原子执行，计数归零移除条目时不会与并发的 begin/end 丢失计数。
        counters.compute(slot) { _, current -> (current ?: 0) + 1 }
        return slot
    }

    fun end(slot: Slot) {
        counters.computeIfPresent(slot) { _, current -> (current - 1).takeIf { it > 0 } }
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

    /**
     * 返回当前在途槽位；计数归零的槽位会立即移除，不会随历史密钥持续增长。
     *
     * 用 [HashMap] 的复制构造器遍历 [ConcurrentHashMap] 的条目，其迭代器弱一致，
     * 并发 end 移除槽位时不会像 [Map.toMap] 的单元素分支那样抛 NoSuchElementException。
     */
    fun snapshot(): Map<Slot, Int> = HashMap(counters)

    data class Slot(val channelId: String, val keyId: String)
}
