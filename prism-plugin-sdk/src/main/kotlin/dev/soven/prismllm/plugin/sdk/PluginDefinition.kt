package dev.soven.prismllm.plugin.sdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

object PluginApi {
    const val VERSION = 1
    const val CHANNEL_PROBE = "diagnostics.channel.probe"
    const val OAUTH_ACCOUNT_PROBE = "diagnostics.oauth-account.probe"
    val permissions = setOf(CHANNEL_PROBE, OAUTH_ACCOUNT_PROBE)
    val contexts = setOf("channel", "oauth-account")
}

@Serializable
data class PluginManifest(
    val id: String,
    val version: String,
    val displayName: String,
    val description: String = "",
    val schemaVersion: Int = 1,
    val apiVersion: Int = PluginApi.VERSION,
    val permissions: List<String> = emptyList(),
    val configuration: List<PluginField> = emptyList(),
)

@Serializable
data class PluginUiDescriptor(
    val schemaVersion: Int = 1,
    val entries: List<PluginUiEntry> = emptyList(),
)

@Serializable
data class PluginUiEntry(
    val id: String,
    val label: String,
    val placement: String,
    val contexts: List<String>,
    val action: PluginAction,
    val icon: String = "bolt",
)

@Serializable
data class PluginAction(
    val id: String,
    val label: String,
    val fields: List<PluginField>,
    val resource: String = "model-test-targets",
    val supportsBatch: Boolean = false,
    val resultKind: String = "probe-v1",
    val notes: List<String> = emptyList(),
)

@Serializable
data class PluginField(
    val key: String,
    val label: String,
    val kind: String,
    val required: Boolean = false,
    val defaultValue: JsonElement? = null,
    val help: String = "",
    val maxLength: Int? = null,
    val min: Double? = null,
    val max: Double? = null,
    val options: List<PluginChoice> = emptyList(),
)

@Serializable
data class PluginChoice(val value: String, val label: String)
