package dev.soven.prismllm.plugin.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@Tag("unit")
class PluginContractTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `manifest and declarative controls round trip without application types`() {
        val manifest = PluginManifest(
            id = "example.probe",
            version = "1.0.0",
            displayName = "示例探测",
            permissions = listOf(PluginApi.CHANNEL_PROBE),
            configuration = listOf(PluginField("prompt", "提示词", "textarea", defaultValue = JsonPrimitive("OK"))),
        )
        assertEquals(manifest, json.decodeFromString<PluginManifest>(json.encodeToString(manifest)))
    }

    @Test
    fun `nonsecret context and outcome retain wire values`() {
        val context = DiagnosticContext(
            DiagnosticTarget("channel", "channel-1", "model-1", "responses", credentialId = "credential-1"),
            buildJsonObject { put("defaultPrompt", "请只回复 OK") },
        )
        val encoded = json.encodeToString(context)
        assertEquals(context, json.decodeFromString<DiagnosticContext>(encoded))
        assertFalse(encoded.contains("accessToken"))
        assertFalse(encoded.contains("baseUrl"))
        assertEquals(ProbeLimits(4_000, 128, 45_000), context.limits)
    }
}
