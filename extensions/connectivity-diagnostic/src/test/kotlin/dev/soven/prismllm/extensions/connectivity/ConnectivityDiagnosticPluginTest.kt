package dev.soven.prismllm.extensions.connectivity

import dev.soven.prismllm.plugin.sdk.DiagnosticContext
import dev.soven.prismllm.plugin.sdk.DiagnosticInput
import dev.soven.prismllm.plugin.sdk.DiagnosticTarget
import dev.soven.prismllm.plugin.sdk.ProbeLimits
import dev.soven.prismllm.plugin.sdk.ProbeOutcome
import dev.soven.prismllm.plugin.sdk.ProbePlan
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("unit")
class ConnectivityDiagnosticPluginTest {
    private val plugin = ConnectivityDiagnosticPlugin()

    @Test
    fun `identity and API versions are independent of SDK artifact version`() {
        assertEquals("prism.connectivity", plugin.id)
        assertEquals("1.0.0", plugin.version)
        assertEquals(1, plugin.apiVersion)
    }

    @Test
    fun `trimmed per-run prompt wins over configuration`() {
        val plan = plugin.plan(input(JsonPrimitive(" \n custom probe \t")), context(JsonPrimitive("configured probe")))
        assertEquals(ProbePlan("custom probe", 128, 45_000), plan)
    }

    @Test
    fun `omitted null and blank per-run prompt use trimmed configured default`() {
        for (prompt in listOf(null, JsonNull, JsonPrimitive(""), JsonPrimitive(" \n\t "))) {
            assertEquals("configured probe", plugin.plan(input(prompt), context(JsonPrimitive("  configured probe  "))).prompt)
        }
    }

    @Test
    fun `missing null or blank configuration uses the exact legacy fallback`() {
        for (default in listOf(null, JsonNull, JsonPrimitive(""), JsonPrimitive(" \n\t "))) {
            assertEquals("请只回复 OK", plugin.plan(input(), context(default)).prompt)
        }
    }

    @Test
    fun `invalid prompt types are not coerced to text`() {
        for (value in listOf(JsonPrimitive(128), JsonPrimitive(true), JsonArray(emptyList()), JsonObject(emptyMap()))) {
            assertFailsWith<IllegalArgumentException> { plugin.plan(input(value), context()) }
            assertFailsWith<IllegalArgumentException> { plugin.plan(input(), context(value)) }
        }
    }

    @Test
    fun `valid per-run override does not read an unused malformed default`() {
        assertEquals("override", plugin.plan(input(JsonPrimitive("override")), context(JsonPrimitive(false))).prompt)
    }

    @Test
    fun `prompt cap applies after trimming and rejects instead of truncating`() {
        val exact = "p".repeat(4_000)
        assertEquals(exact, plugin.plan(input(JsonPrimitive("  $exact \n")), context()).prompt)
        assertFailsWith<IllegalArgumentException> { plugin.plan(input(JsonPrimitive("p".repeat(4_001))), context()) }
        assertFailsWith<IllegalArgumentException> { plugin.plan(input(), context(JsonPrimitive("p".repeat(4_001)))) }
    }

    @Test
    fun `stricter host limits are honored and wider limits never raise official caps`() {
        assertEquals(ProbePlan("OK", 16, 2_000), plugin.plan(input(JsonPrimitive("OK")), context(limits = ProbeLimits(2, 16, 2_000))))
        assertFailsWith<IllegalArgumentException> {
            plugin.plan(input(JsonPrimitive("long")), context(limits = ProbeLimits(3, 128, 45_000)))
        }
        assertEquals(ProbePlan("请只回复 OK", 128, 45_000), plugin.plan(input(), context(limits = ProbeLimits(8_000, 1_024, 120_000))))
    }

    @Test
    fun `non-positive host limits are rejected`() {
        for (limits in listOf(ProbeLimits(0), ProbeLimits(-1), ProbeLimits(maxOutputTokens = 0), ProbeLimits(timeoutMs = 0))) {
            assertFailsWith<IllegalArgumentException> { plugin.plan(input(), context(limits = limits)) }
        }
    }

    @Test
    fun `account and channel actions cannot cross contexts`() {
        assertEquals("请只回复 OK", plugin.plan(input(action = "probe-account"), context(kind = "oauth-account")).prompt)
        assertEquals("请只回复 OK", plugin.plan(input(action = "probe-global"), context()).prompt)
        assertFailsWith<IllegalArgumentException> { plugin.plan(input(action = "probe-account"), context()) }
        assertFailsWith<IllegalArgumentException> { plugin.plan(input(), context(kind = "oauth-account")) }
        assertFailsWith<IllegalArgumentException> { plugin.plan(input(action = "not-declared"), context()) }
    }

    @Test
    fun `plan contains no target selection and ignores caller-supplied limit overrides`() {
        val input = DiagnosticInput("probe-channel", JsonObject(mapOf(
            "prompt" to JsonPrimitive("OK"),
            "model" to JsonPrimitive("other-model"),
            "keyId" to JsonPrimitive("opaque-other-key"),
            "maxOutputTokens" to JsonPrimitive(8_192),
            "timeoutMs" to JsonPrimitive(240_000),
        )))
        assertEquals(ProbePlan("OK", 128, 45_000), plugin.plan(input, context()))
    }

    @Test
    fun `shared plugin keeps no previous execution or configuration state`() {
        assertEquals("first", plugin.plan(input(), context(JsonPrimitive("first"))).prompt)
        assertEquals("second", plugin.plan(input(), context(JsonPrimitive("second"))).prompt)
        assertEquals("请只回复 OK", plugin.plan(input(), context()).prompt)
        plugin.close()
    }

    @Test
    fun `successful report uses safe numeric metrics and literal plain text`() {
        val outcome = ProbeOutcome(true, 200, 32, responseText = " <script>literal text</script> ", usage = JsonObject(mapOf(
            "inputTokens" to JsonPrimitive(10),
            "output_tokens" to JsonPrimitive(2),
            "total_tokens" to JsonPrimitive(12),
            "unrecognized" to JsonPrimitive("not-a-metric"),
        )), upstreamReportedModel = " model-1\u0000\u202e\n ")
        val report = plugin.report(ProbePlan("private prompt"), outcome)
        assertTrue(report.success)
        assertEquals("连通性检测成功", report.title)
        assertEquals("<script>literal text</script>", report.text)
        val metrics = report.metrics.associate { it.label to it.value }
        assertEquals("200", metrics["HTTP 状态"])
        assertEquals("32 ms", metrics["耗时"])
        assertEquals("128", metrics["最大输出 Token"])
        assertEquals("45000 ms", metrics["超时上限"])
        assertEquals("10", metrics["输入 Token"])
        assertEquals("2", metrics["输出 Token"])
        assertEquals("12", metrics["总 Token"])
        assertEquals("model-1", metrics["上游报告模型"])
        assertFalse(report.toString().contains("private prompt"))
        assertFalse(report.toString().contains("not-a-metric"))
    }

    @Test
    fun `upstream 401 remains a failed outcome not a management authentication error`() {
        val report = plugin.report(ProbePlan("OK"), ProbeOutcome(false, 401, 8, responseText = "raw response", errorMessage = "unauthorized"))
        assertFalse(report.success)
        assertEquals("unauthorized", report.text)
        assertEquals("401", report.metrics.first { it.label == "HTTP 状态" }.value)
    }

    @Test
    fun `failed report uses response or bounded generic text when no usable error exists`() {
        assertEquals("response", plugin.report(ProbePlan("OK"), ProbeOutcome(false, 500, 1, responseText = "response", errorMessage = "\u0000")).text)
        val report = plugin.report(ProbePlan("OK"), ProbeOutcome(false, null, -3))
        assertEquals("未返回可用的检测结果", report.text)
        assertEquals("未返回", report.metrics.first { it.label == "HTTP 状态" }.value)
        assertEquals("0 ms", report.metrics.first { it.label == "耗时" }.value)
        assertNull(plugin.report(ProbePlan("OK"), ProbeOutcome(true, 200, 1)).text)
    }

    @Test
    fun `report removes control formatting characters and bounds display text`() {
        val report = plugin.report(ProbePlan("OK"), ProbeOutcome(false, 500, 1, errorMessage = "\u0000\u001b\u202e" + "e".repeat(8_000)))
        assertEquals("e".repeat(4_000), report.text)
        assertTrue(report.notices.any { it.contains("截断") })
        val multiline = plugin.report(ProbePlan("OK"), ProbeOutcome(true, 200, 1, responseText = "a\n\tb\r\u0000c"))
        assertEquals("a\n\tbc", multiline.text)
    }

    @Test
    fun `usage strings negative counters objects and invalid HTTP statuses are not echoed`() {
        val report = plugin.report(ProbePlan("OK"), ProbeOutcome(true, 900, 1, usage = JsonObject(mapOf(
            "input_tokens" to JsonPrimitive("not numeric"),
            "output_tokens" to JsonPrimitive(-1),
            "total_tokens" to JsonObject(mapOf("unrecognized" to JsonPrimitive("value"))),
        ))))
        assertEquals(4, report.metrics.size)
        assertEquals("未返回", report.metrics.first { it.label == "HTTP 状态" }.value)
        assertFalse(report.toString().contains("not numeric"))
    }

    private fun input(prompt: JsonElement? = null, action: String = "probe-channel") = DiagnosticInput(
        action, JsonObject(if (prompt == null) emptyMap() else mapOf("prompt" to prompt)),
    )

    private fun context(default: JsonElement? = null, limits: ProbeLimits = ProbeLimits(), kind: String = "channel") = DiagnosticContext(
        target = DiagnosticTarget(kind, "opaque-target", "saved-model", "chat-completions", credentialId = "opaque-credential"),
        configuration = JsonObject(if (default == null) emptyMap() else mapOf("defaultPrompt" to default)),
        limits = limits,
    )
}
