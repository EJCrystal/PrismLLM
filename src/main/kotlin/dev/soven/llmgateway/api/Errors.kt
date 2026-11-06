package dev.soven.llmgateway.api

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/**
 * 网关内部异常。retryable 只在上游尚未返回首 token 时为 true。
 *
 * @author 文杰
 * @since 2026-10-21
 */
class GatewayException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
    val type: String = "api_error",
    val upstreamCode: String? = null,
    val retryable: Boolean = false,
    val retryAfterMs: Long? = null,
    val upstreamStatus: Int? = null,
    val nativeResponseBody: String? = null,
    /** 仅用于密钥规则匹配与审计的内部错误体，已截断，不返回调用方、不落请求日志。 */
    val upstreamErrorBody: String? = null,
    /** 上游只是读不懂请求形状（如 422、未知角色/字段）；可故障转移，但不冷却当前渠道。 */
    val shapeRefused: Boolean = false,
) : RuntimeException(message)

/**
 * 返回给调用方的 OpenAI 兼容错误信封。
 *
 * @author 文杰
 * @since 2026-10-21
 */
@Serializable
data class ErrorEnvelope(
    val error: ErrorBody,
)

/**
 * 错误正文。上游错误码保留在 upstream_code，不混入 code。
 *
 * @author 文杰
 * @since 2026-10-21
 */
@Serializable
data class ErrorBody(
    val message: String,
    val type: String,
    val code: String? = null,
    val param: String? = null,
    val upstream_code: String? = null,
)

@Serializable
data class AnthropicErrorEnvelope(
    val type: String = "error",
    val error: AnthropicErrorBody,
)

@Serializable
data class AnthropicErrorBody(
    val type: String,
    val message: String,
)

@Serializable
data class GeminiErrorEnvelope(val error: GeminiErrorBody)

@Serializable
data class GeminiErrorBody(val code: Int, val message: String, val status: String)

fun GatewayException.toGeminiEnvelope() = GeminiErrorEnvelope(
    GeminiErrorBody(status.value, message, when (status.value) {
        400, 413, 415, 422 -> "INVALID_ARGUMENT"
        401 -> "UNAUTHENTICATED"
        403 -> "PERMISSION_DENIED"
        404 -> "NOT_FOUND"
        429 -> "RESOURCE_EXHAUSTED"
        503 -> "UNAVAILABLE"
        504 -> "DEADLINE_EXCEEDED"
        else -> "INTERNAL"
    }),
)

fun GatewayException.toEnvelope(): ErrorEnvelope = ErrorEnvelope(
    ErrorBody(
        message = message,
        type = type,
        code = code,
        upstream_code = upstreamCode,
    ),
)

fun GatewayException.toAnthropicEnvelope(): AnthropicErrorEnvelope = AnthropicErrorEnvelope(
    error = AnthropicErrorBody(
        type = anthropicErrorType(status, type),
        message = message,
    ),
)

fun anthropicErrorType(status: HttpStatusCode, fallback: String): String = when (status.value) {
    400, 413, 415, 422 -> "invalid_request_error"
    401 -> "authentication_error"
    403 -> "permission_error"
    404 -> "not_found_error"
    429 -> "rate_limit_error"
    529 -> "overloaded_error"
    else -> fallback.takeIf { it in ANTHROPIC_ERROR_TYPES } ?: "api_error"
}

private val ANTHROPIC_ERROR_TYPES = setOf(
    "invalid_request_error",
    "authentication_error",
    "permission_error",
    "not_found_error",
    "rate_limit_error",
    "api_error",
    "overloaded_error",
)

fun badRequest(code: String, message: String): GatewayException =
    GatewayException(HttpStatusCode.BadRequest, code, message, "invalid_request_error")

fun unauthorized(message: String = "调用方密钥无效或已过期"): GatewayException =
    GatewayException(HttpStatusCode.Unauthorized, "invalid_api_key", message, "authentication_error")

fun forbidden(message: String): GatewayException =
    GatewayException(HttpStatusCode.Forbidden, "forbidden", message, "permission_error")

fun notFound(code: String, message: String): GatewayException =
    GatewayException(HttpStatusCode.NotFound, code, message, "not_found_error")
