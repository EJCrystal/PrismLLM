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

fun GatewayException.toEnvelope(): ErrorEnvelope = ErrorEnvelope(
    ErrorBody(
        message = message,
        type = type,
        code = code,
        upstream_code = upstreamCode,
    ),
)

fun badRequest(code: String, message: String): GatewayException =
    GatewayException(HttpStatusCode.BadRequest, code, message, "invalid_request_error")

fun unauthorized(message: String = "调用方密钥无效或已过期"): GatewayException =
    GatewayException(HttpStatusCode.Unauthorized, "invalid_api_key", message, "authentication_error")

fun forbidden(message: String): GatewayException =
    GatewayException(HttpStatusCode.Forbidden, "forbidden", message, "permission_error")

fun notFound(code: String, message: String): GatewayException =
    GatewayException(HttpStatusCode.NotFound, code, message, "not_found_error")
