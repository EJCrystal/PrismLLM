package dev.soven.prismllm.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlin.random.Random

/**
 * 请求体采样：只抽取控制生成行为的标量参数，不读取任何正文内容。
 *
 * 排障场景是「调用方到底传了多大的 max_output_tokens / max_tokens / budget_tokens」，
 * 因此这里采用已知控制字段的白名单，并把 messages / input / tools / instructions 等
 * 可能含提示词的字段降级为「数量」或「存在」标记。开关默认关闭，启用后也绝不写入
 * prompt、系统提示、工具描述、图片或响应正文；结果只进入 stdout 结构化日志，
 * 不写入 request_logs 表，也不会出现在管理台。
 *
 * @author 文杰
 * @since 2026-11-12
 */
internal object RequestBodySampler {
    private const val MAX_VALUE_LENGTH = 200

    /** 字符串值必须是 token 形态（无空格、无换行），防止调用方把正文塞进控制字段里外泄。 */
    private val SAFE_TOKEN = Regex("[A-Za-z0-9._:/+\\-]{1,$MAX_VALUE_LENGTH}")

    /** 字符串值仅限已知枚举/名称类控制字段，不含任何用户正文。 */
    private val STRING_KEYS = listOf("model", "reasoning_effort", "truncation", "service_tier")

    /** 数值类控制字段；覆盖各协议的输出上限、采样与随机性参数。 */
    private val NUMBER_KEYS = listOf(
        "max_tokens", "max_output_tokens", "max_completion_tokens",
        "temperature", "top_p", "top_k", "frequency_penalty", "presence_penalty",
        "n", "seed", "candidate_count",
    )

    private val BOOLEAN_KEYS = listOf("stream", "store", "parallel_tool_calls")

    /** 命中采样率；rate >= 1 表示全量。失败请求无论采样率都会记录，见 handleProxy 收尾处。 */
    fun shouldSample(rate: Double, roll: Double = Random.nextDouble()): Boolean =
        rate >= 1.0 || roll < rate

    fun sample(request: JsonObject): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()

        STRING_KEYS.forEach { key -> string(request[key])?.let { result[key] = it } }
        NUMBER_KEYS.forEach { key -> number(request[key])?.let { result[key] = it } }
        BOOLEAN_KEYS.forEach { key ->
            (request[key] as? JsonPrimitive)?.booleanOrNull?.let { result[key] = it }
        }

        // Responses 的 reasoning / Messages 的 thinking：只取档位与预算，不取内容。
        (request["reasoning"] as? JsonObject)?.let { reasoning ->
            string(reasoning["effort"])?.let { result["reasoning_effort"] = it }
        }
        (request["thinking"] as? JsonObject)?.let { thinking ->
            string(thinking["type"])?.let { result["thinking_type"] = it }
            number(thinking["budget_tokens"])?.let { result["thinking_budget_tokens"] = it }
        }

        // Gemini 原生请求把生成参数放在 generationConfig / thinkingConfig 里。
        (request["generationConfig"] as? JsonObject)?.let { config ->
            number(config["maxOutputTokens"])?.let { result["max_output_tokens"] = it }
            number(config["candidateCount"])?.let { result["candidate_count"] = it }
            number(config["temperature"])?.let { result["temperature"] = it }
            number(config["topP"])?.let { result["top_p"] = it }
            number(config["topK"])?.let { result["top_k"] = it }
            count(config["stopSequences"])?.let { result["stop_count"] = it }
            (config["thinkingConfig"] as? JsonObject)?.let { thinkingConfig ->
                number(thinkingConfig["thinkingBudget"])?.let { result["thinking_budget_tokens"] = it }
                (thinkingConfig["includeThoughts"] as? JsonPrimitive)?.booleanOrNull
                    ?.let { result["include_thoughts"] = it }
            }
        }

        // 正文类字段一律降级为数量或存在标记，绝不取字符串值。
        count(request["messages"])?.let { result["message_count"] = it }
        count(request["input"])?.let { result["input_item_count"] = it }
        count(request["tools"])?.let { result["tool_count"] = it }
        count(request["stop"])?.let { result["stop_count"] = it }
        count(request["stop_sequences"])?.let { result["stop_count"] = it }
        count(request["safetySettings"])?.let { result["safety_setting_count"] = it }
        listOf("instructions", "system", "system_instruction", "systemInstruction").forEach { key ->
            if (request[key] != null && request[key] !== JsonNull) result["${key}_present"] = true
        }

        (request["response_format"] as? JsonObject)?.let { format ->
            string(format["type"])?.let { result["response_format_type"] = it }
        }
        when (val choice = request["tool_choice"]) {
            is JsonPrimitive -> string(choice)?.let { result["tool_choice"] = it }
            is JsonObject -> string(choice["type"])?.let { result["tool_choice"] = it }
            else -> Unit
        }
        if (request["web_search_options"] != null) result["web_search_options_present"] = true
        return result
    }

    private fun text(element: JsonElement?): String? =
        (element as? JsonPrimitive)
            ?.takeUnless { it == JsonNull }
            ?.contentOrNull
            ?.takeIf { it.length <= MAX_VALUE_LENGTH }

    private fun string(element: JsonElement?): String? = text(element)?.takeIf(SAFE_TOKEN::matches)

    /** 同时兼容 JSON 数字和调用方误传的数字字符串。 */
    private fun number(element: JsonElement?): Number? {
        val raw = text(element) ?: return null
        return raw.toLongOrNull() ?: raw.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun count(element: JsonElement?): Int? = when (element) {
        is JsonArray -> element.size
        is JsonObject -> 1
        is JsonPrimitive -> if (element == JsonNull) null else 1
        else -> null
    }
}
