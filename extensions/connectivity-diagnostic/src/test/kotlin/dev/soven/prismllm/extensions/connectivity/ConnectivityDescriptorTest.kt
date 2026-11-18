package dev.soven.prismllm.extensions.connectivity

import dev.soven.prismllm.plugin.sdk.DiagnosticContext
import dev.soven.prismllm.plugin.sdk.DiagnosticInput
import dev.soven.prismllm.plugin.sdk.DiagnosticPlugin
import dev.soven.prismllm.plugin.sdk.DiagnosticTarget
import dev.soven.prismllm.plugin.sdk.PluginApi
import dev.soven.prismllm.plugin.sdk.PluginManifest
import dev.soven.prismllm.plugin.sdk.PluginUiDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.ServiceLoader
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("unit")
class ConnectivityDescriptorTest {
    private val manifest = Json.decodeFromString<PluginManifest>(resource("manifest.json"))
    private val ui = Json.decodeFromString<PluginUiDescriptor>(resource("ui.json"))

    @Test
    fun `manifest identity permissions and versions match the provided SDK contract`() {
        val plugin = ConnectivityDiagnosticPlugin()
        assertEquals(plugin.id, manifest.id)
        assertEquals(plugin.version, manifest.version)
        assertEquals(1, manifest.schemaVersion)
        assertEquals(plugin.apiVersion, manifest.apiVersion)
        assertEquals(PluginApi.permissions, manifest.permissions.toSet())
        assertEquals(manifest.permissions.size, manifest.permissions.toSet().size)
    }

    @Test
    fun `only defaultPrompt is configurable with the exact legacy fallback and cap`() {
        val field = manifest.configuration.single()
        assertEquals("defaultPrompt", field.key)
        assertEquals("textarea", field.kind)
        assertTrue(field.required)
        assertEquals(JsonPrimitive("请只回复 OK"), field.defaultValue)
        assertEquals(4_000, field.maxLength)
    }

    @Test
    fun `all three placements expose declared structured actions supported by the plugin`() {
        assertEquals(1, ui.schemaVersion)
        assertEquals(setOf("channel-tests", "oauth-account-tests", "plugin-page"), ui.entries.map { it.placement }.toSet())
        assertEquals(setOf("probe-channel", "probe-account", "probe-global"), ui.entries.map { it.action.id }.toSet())
        assertEquals(ui.entries.size, ui.entries.map { it.id }.toSet().size)
        for (entry in ui.entries) {
            assertEquals("model-test-targets", entry.action.resource)
            assertEquals("probe-v1", entry.action.resultKind)
            val kind = entry.contexts.single()
            val plan = ConnectivityDiagnosticPlugin().plan(
                DiagnosticInput(entry.action.id),
                DiagnosticContext(DiagnosticTarget(kind, "opaque-target", "saved-model", "chat-completions")),
            )
            assertEquals("请只回复 OK", plan.prompt)
        }
    }

    @Test
    fun `channel actions include safe model credential protocol scope and prompt controls`() {
        for (entry in ui.entries.filter { it.contexts == listOf("channel") }) {
            assertTrue(entry.action.supportsBatch)
            val fields = entry.action.fields.associateBy { it.key }
            assertEquals(setOf("model", "keyId", "protocol", "scope", "prompt"), fields.keys)
            assertEquals("model", fields.getValue("model").kind)
            assertEquals("credential", fields.getValue("keyId").kind)
            assertEquals("protocol", fields.getValue("protocol").kind)
            assertEquals("scope", fields.getValue("scope").kind)
            assertEquals(JsonPrimitive("single"), fields.getValue("scope").defaultValue)
            assertEquals(setOf("single", "all"), fields.getValue("scope").options.map { it.value }.toSet())
            for (key in listOf("model", "keyId", "protocol")) assertTrue(fields.getValue(key).options.isEmpty())
        }
    }

    @Test
    fun `account mode has no key selector batch or cross-account scope`() {
        val account = ui.entries.single { it.placement == "oauth-account-tests" }
        assertEquals(listOf("oauth-account"), account.contexts)
        assertFalse(account.action.supportsBatch)
        assertEquals(setOf("model", "protocol", "prompt"), account.action.fields.map { it.key }.toSet())
        assertFalse(account.action.fields.any { it.kind == "credential" || it.kind == "scope" })
    }

    @Test
    fun `per-run prompt and action notes document fixed limits and configuration`() {
        for (entry in ui.entries) {
            val prompt = entry.action.fields.single { it.key == "prompt" }
            assertEquals("textarea", prompt.kind)
            assertFalse(prompt.required)
            assertEquals(4_000, prompt.maxLength)
            val notes = entry.action.notes.joinToString("\n")
            for (text in listOf("设置", "4000", "128", "45")) assertTrue(notes.contains(text))
            assertTrue(entry.action.fields.all { it.kind in setOf("model", "credential", "protocol", "scope", "textarea") })
        }
    }

    @Test
    fun `standalone service registration resolves exactly one SDK provider`() {
        val providers = ServiceLoader.load(DiagnosticPlugin::class.java).toList()
        assertEquals(1, providers.size)
        val provider = providers.single()
        assertEquals(ConnectivityDiagnosticPlugin::class.java.name, provider.javaClass.name)
        assertEquals(manifest.id, provider.id)
        assertEquals(manifest.version, provider.version)
        provider.close()
    }

    private fun resource(filename: String): String = requireNotNull(javaClass.getResourceAsStream("/metadata/$filename")) {
        "Missing declarative test metadata"
    }.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
