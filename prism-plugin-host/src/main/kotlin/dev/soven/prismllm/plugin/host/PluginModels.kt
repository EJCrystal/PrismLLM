package dev.soven.prismllm.plugin.host

import dev.soven.prismllm.plugin.sdk.DiagnosticReport
import dev.soven.prismllm.plugin.sdk.DiagnosticTarget
import dev.soven.prismllm.plugin.sdk.PluginField
import dev.soven.prismllm.plugin.sdk.PluginManifest
import dev.soven.prismllm.plugin.sdk.PluginUiDescriptor
import dev.soven.prismllm.plugin.sdk.PluginUiEntry
import dev.soven.prismllm.plugin.sdk.ProbeOutcome
import dev.soven.prismllm.plugin.sdk.ProbePlan
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class PluginCatalogItem(
    val manifest: PluginManifest,
    val ui: PluginUiDescriptor,
    val sha256: String,
    val source: String,
    val compatible: Boolean = true,
    val compatibilityReason: String? = null,
)

/** 持久化管理员的期望，不代表任意进程已经加载了该版本。卸载仍保留该记录。 */
data class PluginInstallation(
    val id: String,
    val version: String,
    val sha256: String,
    val source: String,
    val installed: Boolean,
    val desiredEnabled: Boolean,
    val approvedPermissions: List<String>,
    val configuration: JsonObject,
    val updatedAt: Long,
    val revision: Long = 0,
)

interface PluginInstallationStore {
    suspend fun list(): List<PluginInstallation>
    suspend fun find(id: String): PluginInstallation?
    suspend fun insertIfAbsent(installation: PluginInstallation): Boolean

    /** Persistent stores coordinate the lazy seed read and insert with legacy settings writers. */
    suspend fun getOrCreate(id: String, create: suspend () -> PluginInstallation): PluginInstallation {
        find(id)?.let { return it }
        val installation = create()
        check(installation.id == id)
        if (insertIfAbsent(installation)) return installation
        return find(id) ?: throw PluginHostException("PLUGIN_REVISION_CONFLICT", "Plugin state changed concurrently; refresh and try again", 409)
    }

    suspend fun save(installation: PluginInstallation, expectedRevision: Long): Boolean
}

@Serializable
data class PluginRuntimeState(
    val id: String,
    val displayName: String,
    val description: String,
    val source: String,
    val installed: Boolean,
    val installedVersion: String?,
    val activeVersion: String?,
    val desiredEnabled: Boolean,
    val admissionEnabled: Boolean,
    val requiresRestart: Boolean,
    val status: String,
    val loadError: String? = null,
    val permissions: List<String> = emptyList(),
    val approvedPermissions: List<String> = emptyList(),
    val configuration: JsonObject = JsonObject(emptyMap()),
    val activeConfiguration: JsonObject? = null,
    val configurationFields: List<PluginField> = emptyList(),
    val activeUi: PluginUiDescriptor? = null,
)

@Serializable
data class PluginUiContribution(val pluginId: String, val activeVersion: String, val entry: PluginUiEntry)

@Serializable
data class PluginExecution(
    val pluginId: String,
    val activeVersion: String,
    val target: DiagnosticTarget,
    val plan: ProbePlan,
    val outcome: ProbeOutcome,
    val report: DiagnosticReport,
)

class PluginHostException(val code: String, message: String, val statusCode: Int = 400) : RuntimeException(message)
