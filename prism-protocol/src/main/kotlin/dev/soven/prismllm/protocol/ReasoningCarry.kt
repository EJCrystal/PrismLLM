package dev.soven.prismllm.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Responses 密封推理（`encrypted_content`）的跨轮暂存点。
 *
 * 协议模块保持无状态、无 IO：存放由应用层实现（进程内缓存或持久化存储皆可），
 * 转换函数只通过该接口读写。
 *
 * @author 文杰
 * @since 2026-11-11
 */
public interface ReasoningCarryStore {
    /** 保存一份密封推理载荷，返回可放进调用 ID 的 key；无法保存时返回 null。 */
    fun keep(payload: String): String?

    /** 取回 key 对应的载荷；条目不存在或已过期时返回 null。 */
    fun recall(key: String): String?
}

/**
 * 对齐 magpie 的 reasoning_carry.go：Messages、Chat 客户端不保留 Responses 的 reasoning 项，
 * 于是把工具调用之前收到的密封推理挂在该调用的 ID 上——`<call_id>__rk16__<key>`，
 * 载荷按内容哈希存进 [ReasoningCarryStore]。客户端把调用 ID 原样带回后，下一次请求就能把
 * reasoning 项重新放回 assistant 轮之前，让模型在工具循环里读回自己上一步的推理。
 *
 * 没有 key 的调用 ID 原样保留；后缀在但载荷已过期或丢失时只还原调用 ID，不补推理。
 *
 * @author 文杰
 * @since 2026-11-11
 */
internal object ReasoningCarry {
    private const val MARK = "__rk16__"
    private const val KEY_LENGTH = 16

    /** 给调用 ID 挂上密封推理；没有可回传的推理或无法保存时返回原 ID。 */
    fun attach(id: String, items: List<JsonObject>, store: ReasoningCarryStore?): String {
        if (store == null || id.isEmpty()) return id
        val replayable = items.filter(::replayable)
        if (replayable.isEmpty()) return id
        val payload = JsonArray(replayable).toString()
        val key = store.keep(payload)?.takeIf { it.length == KEY_LENGTH } ?: return id
        return id + MARK + key
    }

    /** 拆出调用自身的 ID 与它携带的密封推理；后缀在但载荷不在时只还原 ID（对齐 magpie）。 */
    fun split(id: String, store: ReasoningCarryStore?): Pair<String, List<JsonObject>> {
        val at = id.length - KEY_LENGTH - MARK.length
        if (at < 1 || !id.startsWith(MARK, at)) return id to emptyList()
        val own = id.substring(0, at)
        val payload = store?.recall(id.substring(at + MARK.length)) ?: return own to emptyList()
        val items = runCatching { Json.parseToJsonElement(payload) }.getOrNull() as? JsonArray
            ?: return own to emptyList()
        return own to items.filterIsInstance<JsonObject>().filter(::replayable)
    }

    /** 能原样回传给 Responses 的推理项：上游给了 encrypted_content（对齐 magpie 的密封项判定）。 */
    fun replayable(item: JsonObject): Boolean =
        item.string("encrypted_content").orEmpty().isNotEmpty()
}

/**
 * 非流式响应转换用：把 tool call 之前出现的密封推理收集起来，挂到下一个调用 ID 上（对齐 magpie）。
 *
 * @author 文杰
 * @since 2026-11-11
 */
internal class SealedReasoningCollector(private val store: ReasoningCarryStore?) {
    private val pending = mutableListOf<JsonObject>()

    fun offer(item: JsonObject) {
        if (ReasoningCarry.replayable(item)) pending += item
    }

    fun callId(id: String): String {
        if (pending.isEmpty()) return id
        val items = pending.toList()
        pending.clear()
        return ReasoningCarry.attach(id, items, store)
    }
}

/** Messages 请求里 tool_use / tool_result 的调用 ID 还原；返回改写后的消息与每个 assistant 轮携带的推理。 */
internal fun recoverMessagesReasoningCarry(
    messages: List<JsonElement>,
    store: ReasoningCarryStore?,
): Pair<List<JsonElement>, Map<Int, List<JsonObject>>> {
    if (store == null) return messages to emptyMap()
    val carried = mutableMapOf<Int, MutableList<JsonObject>>()
    var changed = false
    val rewritten = messages.mapIndexed { index, element ->
        val message = element as? JsonObject ?: return@mapIndexed element
        val content = message["content"] as? JsonArray ?: return@mapIndexed element
        var messageChanged = false
        val blocks = content.map { partElement ->
            val part = partElement as? JsonObject ?: return@map partElement
            when (part.string("type")) {
                "tool_use" -> {
                    val id = part.string("id") ?: return@map part
                    val (own, items) = ReasoningCarry.split(id, store)
                    if (own == id) return@map part
                    messageChanged = true
                    if (items.isNotEmpty()) carried.getOrPut(index) { mutableListOf() } += items
                    JsonObject(part + ("id" to JsonPrimitive(own)))
                }
                "tool_result" -> {
                    val id = part.string("tool_use_id") ?: return@map part
                    val (own, _) = ReasoningCarry.split(id, store)
                    if (own == id) return@map part
                    messageChanged = true
                    JsonObject(part + ("tool_use_id" to JsonPrimitive(own)))
                }
                else -> part
            }
        }
        if (!messageChanged) return@mapIndexed element
        changed = true
        JsonObject(message + ("content" to JsonArray(blocks)))
    }
    return if (changed) rewritten to carried else messages to emptyMap()
}

/** Chat 请求里 tool_calls / tool 消息的调用 ID 还原；返回改写后的消息与每个 assistant 轮携带的推理。 */
internal fun recoverChatReasoningCarry(
    messages: List<JsonElement>,
    store: ReasoningCarryStore?,
): Pair<List<JsonElement>, Map<Int, List<JsonObject>>> {
    if (store == null) return messages to emptyMap()
    val carried = mutableMapOf<Int, MutableList<JsonObject>>()
    var changed = false
    val rewritten = messages.mapIndexed { index, element ->
        val message = element as? JsonObject ?: return@mapIndexed element
        when (message.string("role")) {
            "tool" -> {
                val id = message.string("tool_call_id") ?: return@mapIndexed element
                val (own, _) = ReasoningCarry.split(id, store)
                if (own == id) return@mapIndexed element
                changed = true
                JsonObject(message + ("tool_call_id" to JsonPrimitive(own)))
            }
            "assistant" -> {
                val calls = message["tool_calls"] as? JsonArray ?: return@mapIndexed element
                var messageChanged = false
                val rewrittenCalls = calls.map { callElement ->
                    val call = callElement as? JsonObject ?: return@map callElement
                    val id = call.string("id") ?: return@map call
                    val (own, items) = ReasoningCarry.split(id, store)
                    if (own == id) return@map call
                    messageChanged = true
                    if (items.isNotEmpty()) carried.getOrPut(index) { mutableListOf() } += items
                    JsonObject(call + ("id" to JsonPrimitive(own)))
                }
                if (!messageChanged) return@mapIndexed element
                changed = true
                JsonObject(message + ("tool_calls" to JsonArray(rewrittenCalls)))
            }
            else -> element
        }
    }
    return if (changed) rewritten to carried else messages to emptyMap()
}
