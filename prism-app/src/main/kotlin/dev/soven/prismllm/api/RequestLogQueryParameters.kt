package dev.soven.prismllm.api

import dev.soven.prismllm.requestlog.domain.RequestLogQuery
import dev.soven.prismllm.shared.error.badRequest
import io.ktor.server.application.ApplicationCall

internal fun ApplicationCall.requestLogQuery(): RequestLogQuery {
    fun value(name: String) = request.queryParameters[name]?.trim()?.takeIf { it.isNotEmpty() }
    fun timestamp(name: String): Long? = value(name)?.let {
        it.toLongOrNull()?.takeIf { parsed -> parsed >= 0 }
            ?: throw badRequest("invalid_log_query", "日志时间参数无效")
    }
    val from = timestamp("from")
    val to = timestamp("to")
    if (from != null && to != null && from >= to) throw badRequest("invalid_log_query", "开始时间必须早于结束时间")
    val httpStatus = value("httpStatus")
    if (httpStatus != null && httpStatus !in setOf("2xx", "4xx_5xx") && httpStatus.toIntOrNull() !in 100..599) {
        throw badRequest("invalid_log_query", "HTTP 状态筛选无效")
    }
    val outcome = value("outcome")
    if (outcome != null && outcome !in setOf("success", "recovered", "failed", "cancelled", "other", "abnormal")) {
        throw badRequest("invalid_log_query", "请求结果筛选无效")
    }
    val keyword = value("q")
    if (keyword != null && keyword.length > 2000) throw badRequest("invalid_log_query", "搜索关键词不能超过 2000 个字符")
    return RequestLogQuery(from, to, keyword, value("channelId"), value("model"), httpStatus, outcome)
}
