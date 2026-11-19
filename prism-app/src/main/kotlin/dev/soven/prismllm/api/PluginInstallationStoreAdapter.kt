package dev.soven.prismllm.api

import dev.soven.prismllm.admin.domain.PluginInstallationRecord
import dev.soven.prismllm.admin.persistence.PluginInstallationRepository
import dev.soven.prismllm.plugin.host.PluginInstallation
import dev.soven.prismllm.plugin.host.PluginInstallationStore

/** Bridges the admin persistence model to the host's runtime contract at the app composition boundary. */
internal class PluginInstallationStoreAdapter(
    private val repository: PluginInstallationRepository,
) : PluginInstallationStore {
    override suspend fun list(): List<PluginInstallation> = repository.list().map { it.toRuntime() }

    override suspend fun find(id: String): PluginInstallation? = repository.find(id)?.toRuntime()

    override suspend fun insertIfAbsent(installation: PluginInstallation): Boolean =
        repository.insertIfAbsent(installation.toRecord())

    override suspend fun save(installation: PluginInstallation, expectedRevision: Long): Boolean =
        repository.save(installation.toRecord(), expectedRevision)

    private fun PluginInstallationRecord.toRuntime() = PluginInstallation(
        id, version, sha256, source, installed, desiredEnabled,
        approvedPermissions, configuration, updatedAt, revision,
    )

    private fun PluginInstallation.toRecord() = PluginInstallationRecord(
        id, version, sha256, source, installed, desiredEnabled,
        approvedPermissions, configuration, updatedAt, revision,
    )
}
