package dev.soven.prismllm.extensions.connectivity

import dev.soven.prismllm.plugin.sdk.DiagnosticContext
import dev.soven.prismllm.plugin.sdk.DiagnosticInput
import dev.soven.prismllm.plugin.sdk.DiagnosticMetric
import dev.soven.prismllm.plugin.sdk.DiagnosticPlugin
import dev.soven.prismllm.plugin.sdk.DiagnosticReport
import dev.soven.prismllm.plugin.sdk.ProbeOutcome
import dev.soven.prismllm.plugin.sdk.ProbePlan
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Stateless, SDK-only planning/reporting. The host owns authorization, saved credentials,
 * provider-specific transport, response redaction, timeout enforcement and batch concurrency.
 */
class ConnectivityDiagnosticPlugin : DiagnosticPlugin {
    override val id: String = ID
    override val version: String = VERSION

    override fun plan(input: DiagnosticInput, context: DiagnosticContext): ProbePlan {
        require(input.actionId in ACTIONS) { "不支持的连通性检测操作" }
        val expectedKind = if (input.actionId == "probe-account") "oauth-account" else "channel"
        require(context.target.kind == expectedKind) { "连通性检测操作与上下文不匹配" }
        require(context.limits.maxPromptChars > 0 && context.limits.maxOutputTokens > 0 && context.limits.timeoutMs > 0) {
            "连通性检测限制必须为正数"
        }
        val prompt = textValue(input.values, "prompt", "测试提示词")
            ?: textValue(context.configuration, "defaultPrompt", "默认提示词")
            ?: DEFAULT_PROMPT
        val promptLimit = minOf(MAX_PROMPT_CHARS, context.limits.maxPromptChars)
        require(prompt.length <= promptLimit) { "测试提示词不能超过 $promptLimit 个字符" }
        // Target/model/protocol/key selections are deliberately not part of ProbePlan.
        return ProbePlan(
            prompt = prompt,
            maxOutputTokens = minOf(MAX_OUTPUT_TOKENS, context.limits.maxOutputTokens),
            timeoutMs = minOf(TIMEOUT_MS, context.limits.timeoutMs),
        )
    }

    override fun report(plan: ProbePlan, outcome: ProbeOutcome): DiagnosticReport {
        val metrics = mutableListOf(
            DiagnosticMetric("HTTP 状态", outcome.statusCode?.takeIf { it in 100..599 }?.toString() ?: "未返回"),
            DiagnosticMetric("耗时", "${outcome.latencyMs.coerceAtLeast(0)} ms"),
            DiagnosticMetric("最大输出 Token", plan.maxOutputTokens.toString()),
            DiagnosticMetric("超时上限", "${plan.timeoutMs} ms"),
        )
        // Only recognized non-negative numeric counters are exposed, never arbitrary usage JSON.
        addUsageMetric(metrics, outcome.usage, "输入 Token", "inputTokens", "input_tokens", "prompt_tokens", "promptTokenCount")
        addUsageMetric(metrics, outcome.usage, "输出 Token", "outputTokens", "output_tokens", "completion_tokens", "candidatesTokenCount")
        addUsageMetric(metrics, outcome.usage, "总 Token", "totalTokens", "total_tokens", "totalTokenCount")
        val model = boundedText(outcome.upstreamReportedModel, 120, multiline = false)
        model?.let { metrics += DiagnosticMetric("上游报告模型", it.value) }
        val text = if (outcome.success) {
            boundedText(outcome.responseText, MAX_REPORT_CHARS)
        } else {
            boundedText(outcome.errorMessage, MAX_REPORT_CHARS)
                ?: boundedText(outcome.responseText, MAX_REPORT_CHARS)
        }
        val notices = mutableListOf(
            "管理面探测不写请求日志、不消耗调用方密钥额度，也不触发冷却或自动停用。",
            "结果只代表本次请求；成功不证明模型身份、质量或长期可用性。",
        )
        if (text?.truncated == true) notices += "展示文本已截断至 $MAX_REPORT_CHARS 个字符。"
        if (model?.truncated == true) notices += "上游报告模型名称已截断。"
        return DiagnosticReport(
            success = outcome.success,
            title = if (outcome.success) "连通性检测成功" else "连通性检测失败",
            metrics = metrics,
            notices = notices,
            // Plain bounded text; the generic host UI must escape it, never interpret HTML.
            text = text?.value ?: if (!outcome.success) "未返回可用的检测结果" else null,
        )
    }

    private fun textValue(values: JsonObject, key: String, label: String): String? {
        val value = values[key] ?: return null
        if (value == JsonNull) return null
        require(value is JsonPrimitive && value.isString) { "$label 必须是文本" }
        return value.content.trim().takeIf { it.isNotEmpty() }
    }

    private fun addUsageMetric(metrics: MutableList<DiagnosticMetric>, usage: JsonObject?, label: String, vararg keys: String) {
        val count = keys.firstNotNullOfOrNull { key ->
            (usage?.get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }
        }
        count?.let { metrics += DiagnosticMetric(label, it.toString()) }
    }

    private data class BoundedText(val value: String, val truncated: Boolean)

    private fun boundedText(value: String? = null, limit: Int, multiline: Boolean = true): BoundedText? {
        if (value == null) return null
        val printable = value.trim().asSequence()
            .filter { char ->
                (!char.isISOControl() || (multiline && char in "\n\t")) && Character.getType(char) != Character.FORMAT.toInt()
            }
            .take(limit + 1)
            .joinToString("")
        val bounded = printable.take(limit).trim().takeIf { it.isNotEmpty() } ?: return null
        return BoundedText(bounded, printable.length > limit)
    }

    companion object {
        const val ID = "prism.connectivity"
        const val VERSION = "1.0.0"
        const val DEFAULT_PROMPT = "请只回复 OK"
        const val MAX_PROMPT_CHARS = 4_000
        const val MAX_OUTPUT_TOKENS = 128
        const val TIMEOUT_MS = 45_000L
        const val MAX_REPORT_CHARS = 4_000
        private val ACTIONS = setOf("probe-channel", "probe-account", "probe-global")
    }
}
