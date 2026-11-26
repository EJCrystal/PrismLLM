package dev.soven.prismllm.antigravity

import dev.soven.prismllm.config.AntigravityAcpConfig
import dev.soven.prismllm.quota.domain.ClientKeyRecord
import dev.soven.prismllm.shared.error.GatewayError
import dev.soven.prismllm.shared.error.forbidden
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import java.nio.file.Path

internal class AntigravityAcpGateway(
    private val config: AntigravityAcpConfig,
    private val pilot: AntigravityAcpPilot = AntigravityAcpPilot(
        command = config.command(),
        workspace = config.stateDir,
        agentWorkspace = Path.of("/workspace"),
        timeoutMs = config.timeoutMs,
        cancelGraceMs = 3_000,
        allowModelSelection = false,
    ),
    private val onCancelled: (Boolean) -> Unit = {},
) {
    val modelId: String get() = config.modelId
    private val admission = Mutex()

    fun authorize(key: ClientKeyRecord) {
        if (key.id !in config.clientKeyIds) throw forbidden("此密钥未获授权访问 Antigravity ACP")
        val policy = key.accessPolicy
        if (policy.allowedChannelIds != null || policy.allowedChannelKeys != null ||
            !policy.deniedChannelIds.isNullOrEmpty() || !policy.deniedChannelKeys.isNullOrEmpty()) {
            throw forbidden("Antigravity ACP 尚不支持带渠道访问限制的调用方密钥")
        }
        val allowedModels = policy.allowedModelIds
        if ((allowedModels != null && modelId !in allowedModels) ||
            modelId in policy.deniedModelIds.orEmpty()) {
            throw forbidden("此密钥的访问范围不允许使用固定的 Antigravity 模型")
        }
        if (key.quotas.dailyTokens != null || key.quotas.dailyUsdLimit != null) {
            throw forbidden("ACP 不提供可靠的 token/费用用量；不能使用带每日 token 或费用限额的密钥")
        }
    }

    suspend fun <Result> withAdmission(block: suspend () -> Result): Result {
        if (!admission.tryLock()) throw AcpPilotException(AcpPilotException.Kind.BUSY).gatewayError()
        try {
            return block()
        } finally {
            admission.unlock()
        }
    }

    suspend fun prompt(text: String, onText: suspend (String) -> Unit): String {
        var received = 0L
        val failureWindow = StringBuilder()
        val reason = try {
            pilot.prompt(text, modelId = modelId, onCancelled = onCancelled) { chunk ->
                received += chunk.length
                if (received > 1_048_576) {
                    throw GatewayError(HttpStatusCode.BadGateway, "antigravity_acp_output_too_large",
                        "ACP 输出超过网关限制，已停止本次请求", "upstream_error")
                }
                failureWindow.append(chunk)
                acpServiceFailure(failureWindow.toString())?.let { throw AcpPilotException(it) }
                if (failureWindow.length > 1_024) failureWindow.delete(0, failureWindow.length - 1_024)
                onText(chunk)
            }
        } catch (failure: AcpPilotException) {
            throw failure.gatewayError()
        }
        return when (reason) {
            AcpStopReason.END_TURN -> "stop"
            AcpStopReason.MAX_TOKENS, AcpStopReason.MAX_TURN_REQUESTS -> "length"
            AcpStopReason.REFUSAL -> "content_filter"
            AcpStopReason.CANCELLED -> throw GatewayError(HttpStatusCode.BadGateway,
                "antigravity_acp_cancelled", "ACP 已取消本次请求，未重试或回退", "upstream_error")
        }
    }
}

private fun AcpPilotException.gatewayError(): GatewayError {
    val status = when (kind) {
        AcpPilotException.Kind.BUSY, AcpPilotException.Kind.QUOTA_EXHAUSTED -> HttpStatusCode.TooManyRequests
        AcpPilotException.Kind.SERVICE_DISABLED -> HttpStatusCode.Forbidden
        AcpPilotException.Kind.TIMED_OUT -> HttpStatusCode.GatewayTimeout
        AcpPilotException.Kind.START_FAILED -> HttpStatusCode.ServiceUnavailable
        AcpPilotException.Kind.MODEL_UNAVAILABLE, AcpPilotException.Kind.MODEL_MISMATCH -> HttpStatusCode.Conflict
        else -> HttpStatusCode.BadGateway
    }
    return GatewayError(status, "antigravity_acp_" + kind.name.lowercase(), kind.description,
        if (status == HttpStatusCode.TooManyRequests) "rate_limit_error" else "upstream_error")
}
