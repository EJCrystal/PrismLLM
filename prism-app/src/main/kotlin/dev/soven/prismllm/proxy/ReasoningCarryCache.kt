package dev.soven.prismllm.proxy

import dev.soven.prismllm.protocol.ReasoningCarryStore
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Responses 密封推理的进程内暂存（对齐 magpie 的 gemini_sigstore 文件库）。
 *
 * 工具调用 ID 只携带载荷哈希的 key（`__rk16__` 后的 16 个字符），载荷本身存在这里。
 * 同一份载荷只占一条；90 天未读取即过期，条目超过上限时淘汰最久未用的。
 * 进程重启或换实例后旧 key 取不到，调用 ID 仍会被还原，只是不再补推理——与 magpie 丢文件后的行为一致。
 *
 * @author 文杰
 * @since 2026-11-11
 */
class ReasoningCarryCache(
    private val maxEntries: Int = 2048,
    private val keepMillis: Long = KEEP_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) : ReasoningCarryStore {
    private data class Entry(val payload: String, val scope: String, @Volatile var seenAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val prunedAt = AtomicLong(0)

    /**
     * 按渠道隔离的视图：密封推理只有回到签发它的渠道才能取回。
     *
     * 网关会在多渠道间故障转移或轮换，而 `encrypted_content` 由签发它的上游账号加密，
     * 换一个账号回放通常会被上游拒绝（magpie 用「记住拒绝并重试」处理同一问题）。
     * 这里直接按渠道隔离，换渠道时只还原调用 ID、不补推理，代价是丢失一轮推理上下文，
     * 而不是让整次请求以 400 失败。
     */
    fun scoped(scope: String): ReasoningCarryStore = Scoped(this, scope)

    override fun keep(payload: String): String = keepFor("", payload)

    override fun recall(key: String): String? = recallFor(key, "")

    private fun keepFor(scope: String, payload: String): String {
        val key = keyOf(scope + SCOPE_SEPARATOR + payload)
        entries[key] = Entry(payload, scope, clock())
        prune()
        return key
    }

    private fun recallFor(key: String, scope: String): String? {
        val entry = entries[key] ?: return null
        if (entry.scope != scope) return null
        val now = clock()
        if (now - entry.seenAt > keepMillis) {
            entries.remove(key, entry)
            return null
        }
        entry.seenAt = now
        return entry.payload
    }

    private fun prune() {
        val now = clock()
        val last = prunedAt.get()
        if (now - last < PRUNE_INTERVAL_MILLIS || !prunedAt.compareAndSet(last, now)) return
        entries.entries.removeIf { now - it.value.seenAt > keepMillis }
        val overflow = entries.size - maxEntries
        if (overflow > 0) {
            entries.entries.sortedBy { it.value.seenAt }.take(overflow).forEach { entries.remove(it.key) }
        }
    }

    private class Scoped(
        private val cache: ReasoningCarryCache,
        private val scope: String,
    ) : ReasoningCarryStore {
        override fun keep(payload: String): String = cache.keepFor(scope, payload)

        override fun recall(key: String): String? = cache.recallFor(key, scope)
    }

    private companion object {
        const val KEEP_MILLIS = 90L * 24 * 60 * 60 * 1000
        const val PRUNE_INTERVAL_MILLIS = 60L * 1000
        const val KEY_BYTES = 12

        /** 拼接 scope 与载荷时用 NUL 分隔，避免不同 scope 拼接出同一段文本。 */
        const val SCOPE_SEPARATOR = '\u0000'

        /** 12 字节 SHA-256 前缀的 base64url，正好 16 个字符，与 magpie 的 key 长度一致。 */
        fun keyOf(payload: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.copyOf(KEY_BYTES))
        }
    }
}
