package dev.soven.prismllm.plugin.host

import dev.soven.prismllm.plugin.sdk.DiagnosticInput
import dev.soven.prismllm.plugin.sdk.DiagnosticTarget
import dev.soven.prismllm.plugin.sdk.ProbeOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Opt-in check of the independently built official artifact; transports are local fakes only. */
@Tag("component")
@EnabledIfSystemProperty(named = "prism.plugin.fixtureDirectory", matches = ".+")
internal class OfficialPluginPackageComponentTest : PluginTestSupport() {
    @Test
    fun `built official package validates stages cold-loads and executes both granted host modes`(): Unit = runBlocking {
        val fixtureDirectory = Path.of(System.getProperty("prism.plugin.fixtureDirectory"))
        Files.createDirectory(catalogDirectory)
        Files.copy(fixtureDirectory.resolve("catalog.json"), catalogDirectory.resolve("catalog.json"))
        Files.copy(fixtureDirectory.resolve("prism-connectivity-1.0.0.prismplugin"), catalogDirectory.resolve("prism-connectivity-1.0.0.prismplugin"))
        val store = MemoryInstallationStore()
        val manual = runtime(store)
        manual.initialize()
        val item = manual.catalogItems().single()
        assertEquals("prism.connectivity", item.manifest.id)
        assertTrue(item.compatible)
        val installation = manual.install(item.manifest.id, "1.0.0", item.manifest.permissions)
        assertNull(installation.activeVersion)
        manual.configure(item.manifest.id, buildJsonObject { put("defaultPrompt", "component default") })
        val enabled = manual.setEnabled(item.manifest.id, true)
        assertTrue(enabled.requiresRestart)
        assertFalse(enabled.admissionEnabled)
        manual.close()
        val host = runtime(store)
        host.initialize()
        assertEquals(3, host.contributions().size)
        val channel = DiagnosticTarget("channel", "channel-1", "model-1", "responses", credentialId = "opaque-key-1")
        val values = buildJsonObject { put("prompt", "  " + "x".repeat(5_000) + "  ") }
        val failed = host.execute(item.manifest.id, "1.0.0", DiagnosticInput("probe-channel", values), channel) { plan ->
            assertEquals(4_000, plan.prompt.length)
            assertEquals(128, plan.maxOutputTokens)
            assertEquals(45_000L, plan.timeoutMs)
            ProbeOutcome(false, 401, 5, errorMessage = "Local fake upstream rejection")
        }
        assertEquals(401, failed.outcome.statusCode)
        assertFalse(failed.report.success)
        val account = DiagnosticTarget("oauth-account", "account-1", "model-2", "gemini")
        val native = host.execute(item.manifest.id, "1.0.0", DiagnosticInput("probe-account"), account) { plan ->
            assertEquals("component default", plan.prompt)
            ProbeOutcome(true, 200, 7, responseText = "OK")
        }
        assertEquals(account, native.target)
        assertTrue(native.report.success)
        host.configure(item.manifest.id, buildJsonObject { put("defaultPrompt", "updated default") })
        val updated = host.execute(item.manifest.id, "1.0.0", DiagnosticInput("probe-global"), channel) { ProbeOutcome(true, 200, 3, responseText = "OK") }
        assertEquals("updated default", updated.plan.prompt)
    }
}
