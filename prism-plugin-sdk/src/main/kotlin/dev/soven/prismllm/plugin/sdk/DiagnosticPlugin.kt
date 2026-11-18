package dev.soven.prismllm.plugin.sdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 可信 JVM 插件只制定计划和报告；已授权目标、凭据准备及真实 HTTP 发送属于宿主。 */
interface DiagnosticPlugin : AutoCloseable {
    val id: String
    val version: String
    val apiVersion: Int get() = PluginApi.VERSION

    fun plan(input: DiagnosticInput, context: DiagnosticContext): ProbePlan
    fun report(plan: ProbePlan, outcome: ProbeOutcome): DiagnosticReport
    override fun close() = Unit
}

@Serializable
data class DiagnosticInput(val actionId: String, val values: JsonObject = JsonObject(emptyMap()))

@Serializable
data class DiagnosticContext(
    val target: DiagnosticTarget,
    val configuration: JsonObject = JsonObject(emptyMap()),
    val limits: ProbeLimits = ProbeLimits(),
)

@Serializable
data class DiagnosticTarget(
    val kind: String,
    val id: String,
    val model: String,
    val protocol: String,
    val displayName: String = "",
    val credentialId: String? = null,
    val credentialName: String? = null,
)

@Serializable
data class ProbeLimits(
    val maxPromptChars: Int = 4_000,
    val maxOutputTokens: Int = 128,
    val timeoutMs: Long = 45_000,
)

/** 不含 URL、密钥或目标选择；宿主把计划绑定到此次请求已经授权的目标。 */
@Serializable
data class ProbePlan(
    val prompt: String,
    val maxOutputTokens: Int = 128,
    val timeoutMs: Long = 45_000,
)

@Serializable
data class ProbeOutcome(
    val success: Boolean,
    val statusCode: Int?,
    val latencyMs: Long,
    val responseText: String? = null,
    val errorMessage: String? = null,
    val usage: JsonObject? = null,
    val upstreamReportedModel: String? = null,
)

@Serializable
data class DiagnosticReport(
    val success: Boolean,
    val title: String,
    val metrics: List<DiagnosticMetric> = emptyList(),
    val notices: List<String> = emptyList(),
    val text: String? = null,
)

@Serializable
data class DiagnosticMetric(val label: String, val value: String)
