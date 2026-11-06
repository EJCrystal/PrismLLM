package dev.soven.llmgateway.oauth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.OffsetDateTime

/**
 * 从上游 429 响应体提取重置时间，返回相对当前时间的毫秒数。
 *
 * 只识别常见的结构化字段，不读取或保留响应正文：
 * `resets_in_seconds` / `resets_at`（ChatGPT）、`retry_after*`、
 * 以及 Google RPC 的 `error.details[].retryDelay`。
 *
 * @author 文杰
 * @since 2026-11-06
 */
internal fun parseRateLimitResetMs(body: String?, now: Long = System.currentTimeMillis()): Long? {
    val root = body
        ?.takeIf { it.isNotBlank() }
        ?.let { text -> runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() }
        ?: return null

    val objects = buildList {
        add(root)
        (root["error"] as? JsonObject)?.let(::add)
        (root["detail"] as? JsonObject)?.let(::add)
    }
    for (obj in objects) {
        secondsHint(obj, "resets_in_seconds")?.let { return it }
        secondsHint(obj, "retry_after_seconds")?.let { return it }
        secondsHint(obj, "retry_after")?.let { return it }
        millisHint(obj, "retry_after_ms")?.let { return it }
        epochHint(obj, "resets_at", now)?.let { return it }
        epochHint(obj, "reset_at", now)?.let { return it }
    }

    val details = (root["error"] as? JsonObject)?.get("details") as? JsonArray ?: return null
    for (detail in details) {
        val delay = ((detail as? JsonObject)?.get("retryDelay") as? JsonPrimitive)?.contentOrNull
        parseDurationMs(delay)?.let { return it }
    }
    return null
}

private fun secondsHint(obj: JsonObject, key: String): Long? =
    number(obj[key])?.let { it * 1_000 }?.takeIf { it > 0 }

private fun millisHint(obj: JsonObject, key: String): Long? =
    number(obj[key])?.takeIf { it > 0 }

private fun epochHint(obj: JsonObject, key: String, now: Long): Long? {
    val text = (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val epochMs = text.toLongOrNull()
        ?.let { value -> if (value > EPOCH_MILLIS_THRESHOLD) value else value * 1_000 }
        ?: runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
        ?: return null
    return (epochMs - now).takeIf { it > 0 }
}

private fun number(element: JsonElement?): Long? {
    val primitive = element as? JsonPrimitive ?: return null
    return primitive.longOrNull ?: primitive.contentOrNull?.trim()?.toLongOrNull()
}

private fun parseDurationMs(value: String?): Long? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val match = DURATION.matchEntire(text) ?: return null
    val amount = match.groupValues[1].toDoubleOrNull() ?: return null
    val unitMs = when (match.groupValues[2]) {
        "ms" -> 1.0
        "s" -> 1_000.0
        "m" -> 60_000.0
        "h" -> 3_600_000.0
        else -> return null
    }
    return (amount * unitMs).toLong().takeIf { it > 0 }
}

/** 13 位以上按毫秒解释，10 位按秒解释。 */
private const val EPOCH_MILLIS_THRESHOLD = 10_000_000_000L
private val DURATION = Regex("""^(\d+(?:\.\d+)?)(ms|s|m|h)$""")
