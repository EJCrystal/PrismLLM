package dev.soven.prismllm.plugin.sdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Trusted plugin implementations must be thread-safe: the host may call [plan] and [report]
 * concurrently, including multiple calls on the same instance. The host may also call [close]
 * while either method is still running. Keep mutable state synchronized and make [close] safe to
 * overlap pending work. Each call has a bounded host wait, but uncooperative JVM code cannot be
 * forcibly stopped; a call that outlives its deadline may continue in the background.
 *
 * Plugins only formulate plans and reports; the authorized target, credential preparation and
 * actual HTTP request remain host-owned.
 */
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

/** Report fields are bounded presentation; host outcome.success remains the probe verdict. */
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
