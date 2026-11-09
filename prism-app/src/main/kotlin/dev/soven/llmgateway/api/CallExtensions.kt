package dev.soven.llmgateway.api

import dev.soven.llmgateway.shared.error.badRequest

import io.ktor.server.application.ApplicationCall

/**
 * Ktor [ApplicationCall] 通用请求参数提取扩展，统一参数判空、类型转换与错误响应格式。
 *
 * @author 文杰
 * @since 2026-10-24
 */

/**
 * 必填路径参数提取器。缺失或空白时抛出 400 [badRequest] 统一异常。
 */
fun ApplicationCall.requiredParam(name: String, message: String? = null): String {
    val value = parameters[name]?.trim()
    if (value.isNullOrEmpty()) {
        throw badRequest("missing_$name", message ?: "缺少参数 $name")
    }
    return value
}

/**
 * 必填查询参数提取器。缺失或空白时抛出 400 [badRequest] 统一异常。
 */
fun ApplicationCall.requiredQuery(name: String, message: String? = null): String {
    val value = request.queryParameters[name]?.trim()
    if (value.isNullOrEmpty()) {
        throw badRequest("missing_$name", message ?: "缺少参数 $name")
    }
    return value
}

/**
 * 整数查询参数提取器，支持默认值及可选的数值上下限约束。
 */
fun ApplicationCall.queryInt(
    name: String,
    default: Int,
    min: Int? = null,
    max: Int? = null,
): Int {
    val parsed = request.queryParameters[name]?.toIntOrNull() ?: default
    return when {
        min != null && max != null -> parsed.coerceIn(min, max)
        min != null -> parsed.coerceAtLeast(min)
        max != null -> parsed.coerceAtMost(max)
        else -> parsed
    }
}

/**
 * 可选的长整型（Long）查询参数提取器。
 */
fun ApplicationCall.queryLongOrNull(name: String): Long? =
    request.queryParameters[name]?.trim()?.toLongOrNull()
