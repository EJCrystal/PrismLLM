package dev.soven.prismllm.antigravity

import dev.soven.prismllm.config.AntigravityAgyConfig
import dev.soven.prismllm.oauth.domain.OAuthAccountRecord
import dev.soven.prismllm.shared.error.GatewayError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 普通数据面使用的官方 agy ACP 桥接器。
 *
 * 每个 profile 独占一个 ACP turn，凭据始终留在 agy profile 目录；网关不解析、复制或刷新
 * Google token。每次启动官方 agent 都重新核验固定模型和 Pro 档位，验收报告缺失时由
 * gateway-agent 拒绝启动，不允许回退到旧 OAuth 或其他模型。
 */
internal class AntigravityAgyGateway(
    private val config: AntigravityAgyConfig,
    private val onCancelled: (Boolean) -> Unit = {},
    private val pilotFactory: (List<String>, Path) -> AntigravityAcpPilot = { command, state ->
        AntigravityAcpPilot(
            command = command,
            workspace = state,
            environment = config.proxyEnvironment(),
            agentWorkspace = Path.of("/workspace"),
            timeoutMs = 120_000,
            cancelGraceMs = 3_000,
        )
    },
) : AutoCloseable {
    private val admissions = ConcurrentHashMap<String, Mutex>()

    @Volatile
    private var closed = false

    suspend fun prompt(
        account: OAuthAccountRecord,
        modelId: String,
        text: String,
        onText: suspend (String) -> Unit,
    ): String {
        if (closed) throw GatewayError(HttpStatusCode.ServiceUnavailable, "antigravity_agy_disabled", "官方 agy 已关闭，不接受新的生成请求", "upstream_error")
        val profileId = account.agyProfileId?.takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }
            ?: throw GatewayError(
                HttpStatusCode.Conflict,
                "antigravity_agy_profile_missing",
                "Antigravity agy 档案未绑定，不能进入普通数据面",
                "upstream_error",
            )
        if (!account.isAgy || account.accountPlan != "PRO") {
            throw GatewayError(
                HttpStatusCode.Forbidden,
                "antigravity_agy_subscription_required",
                "仅已验证的 Google AI Pro agy 档案可进入 Antigravity 数据面",
                "permission_error",
            )
        }
        if (account.proxyUrl != null) {
            throw GatewayError(
                HttpStatusCode.BadRequest,
                "antigravity_agy_proxy_unsupported",
                "agy 数据面使用隔离环境的官方出口，不接受账号级代理覆盖",
                "invalid_request_error",
            )
        }
        if (modelId !in account.modelCatalog.orEmpty().map { it.upstreamModel } && account.modelCatalog != null) {
            throw GatewayError(
                HttpStatusCode.Conflict,
                "antigravity_agy_model_unavailable",
                "请求模型不在该 agy 档案的已验收目录中，不切换模型或回退",
                "upstream_error",
            )
        }
        val state = config.stateDir.resolve(profileId)
        if (!Files.exists(state, NOFOLLOW_LINKS) || !Files.isDirectory(state, NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(state)
        ) {
            throw GatewayError(
                HttpStatusCode.ServiceUnavailable,
                "antigravity_agy_profile_unavailable",
                "官方 agy 档案目录不可用，请在管理台重新授权",
                "upstream_error",
            )
        }
        val admission = admissions.computeIfAbsent(profileId) { Mutex() }
        if (!admission.tryLock()) throw AcpPilotException(AcpPilotException.Kind.BUSY).gatewayError()
        try {
            val pilot = pilotFactory(config.gatewayCommand(state, modelId), state)
            return try {
                when (pilot.prompt(
                    text = text,
                    modelId = modelId,
                    requiredTier = "PRO",
                    onCancelled = onCancelled,
                    onText = onText,
                )) {
                    AcpStopReason.END_TURN -> "stop"
                    AcpStopReason.MAX_TOKENS, AcpStopReason.MAX_TURN_REQUESTS -> "length"
                    AcpStopReason.REFUSAL -> "content_filter"
                    AcpStopReason.CANCELLED -> throw GatewayError(
                        HttpStatusCode.BadGateway,
                        "antigravity_acp_cancelled",
                        "ACP 已取消本次请求，未重试或回退",
                        "upstream_error",
                    )
                }
            } catch (failure: AcpPilotException) {
                throw failure.gatewayError()
            }
        } finally {
            admission.unlock()
        }
    }

    override fun close() {
        closed = true
        admissions.clear()
    }
}
