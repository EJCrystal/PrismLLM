package dev.soven.prismllm.proxy.codeassist

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Antigravity 会对少数系统提示行返回 429 "Resource has been exhausted"，
 * 但这不是额度问题。magpie 的做法是在命中行的第一个字符后插入零宽空格，
 * 模型读得懂，Google 的检查看不出来。这里照搬同一份行清单与处理方式。
 *
 * @author 文杰
 * @since 2026-11-06
 */
object CodeAssistCloak {
    private const val ZERO_WIDTH = "\u200b"

    /** 实测会被拒的系统提示行，全部小写。 */
    private val turnedAway = listOf(
        "built on anthropic's claude agent sdk",
        "you are codex, an agent based on gpt-5",
        "you are codex, a coding agent based on gpt-5",
        "hermes agent, an intelligent ai assistant created by nous research",
    )

    fun turnsAway(system: String): Boolean {
        val lower = system.lowercase()
        return turnedAway.any { lower.contains(it) }
    }

    /** 在每条命中行的第一个字符后插入零宽空格；其余内容保持原样。 */
    fun cloak(system: String): String {
        if (!turnsAway(system)) return system
        val lower = asciiLower(system)
        if (lower.length != system.length) return system
        val positions = sortedSetOf<Int>()
        for (line in turnedAway) {
            var cursor = 0
            while (true) {
                val index = lower.indexOf(line, cursor)
                if (index < 0) break
                positions.add(index + 1)
                cursor = index + line.length
            }
        }
        if (positions.isEmpty()) return system
        val builder = StringBuilder(system.length + positions.size * ZERO_WIDTH.length)
        var previous = 0
        for (position in positions) {
            builder.append(system, previous, position)
            builder.append(ZERO_WIDTH)
            previous = position
        }
        builder.append(system, previous, system.length)
        return builder.toString()
    }

    /** 只改 systemInstruction 里的文本，其他字段不动。 */
    fun apply(body: JsonObject): JsonObject {
        val instruction = body["systemInstruction"] as? JsonObject ?: return body
        val parts = instruction["parts"] as? JsonArray ?: return body
        var changed = false
        val cloakedParts = parts.map { part ->
            val obj = part as? JsonObject
            val text = (obj?.get("text") as? JsonPrimitive)?.content
            if (obj != null && text != null) {
                val cloaked = cloak(text)
                if (cloaked != text) {
                    changed = true
                    return@map JsonObject(obj + ("text" to JsonPrimitive(cloaked)))
                }
            }
            part
        }
        if (!changed) return body
        return JsonObject(
            body + ("systemInstruction" to JsonObject(instruction + ("parts" to JsonArray(cloakedParts)))),
        )
    }

    /** 上游的拒绝话术：出现在 429 或流内错误事件里，都不代表额度耗尽。 */
    fun isRefusal(message: String): Boolean =
        message.contains("resource has been exhausted", ignoreCase = true)

    private fun asciiLower(value: String): String {
        val chars = value.toCharArray()
        for (index in chars.indices) {
            val char = chars[index]
            if (char in 'A'..'Z') chars[index] = char + 32
        }
        return String(chars)
    }
}
