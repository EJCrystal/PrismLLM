package dev.soven.prismllm.api

import dev.soven.prismllm.admin.domain.PluginInstallationRecord
import dev.soven.prismllm.admin.persistence.PluginInstallationRepository
import dev.soven.prismllm.db.DiagnosticPreparationDeadline
import dev.soven.prismllm.plugin.host.PluginHostException
import dev.soven.prismllm.plugin.host.PluginInstallation
import dev.soven.prismllm.plugin.host.PluginInstallationStore
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/** Bridges the admin persistence model to the host's runtime contract at the app composition boundary. */
internal class PluginInstallationStoreAdapter(
    private val repository: PluginInstallationRepository,
    private val database: Database,
    private val connectivitySettingsLock: ConnectivitySettingsLock,
) : PluginInstallationStore {
    override suspend fun list(): List<PluginInstallation> = repository.list().map { it.toRuntime() }

    override suspend fun find(id: String): PluginInstallation? =
        (DiagnosticPreparationDeadline.withinSynchronousAndDrain(2_000) { StoredInstallation(repository.find(id)?.toRuntime()) }
            ?: throw PluginHostException("PLUGIN_BUSY", "Timed out reading plugin installation", 503)).value

    private class StoredInstallation(val value: PluginInstallation?)

    override suspend fun insertIfAbsent(installation: PluginInstallation): Boolean =
        repository.insertIfAbsent(installation.toRecord())

    override suspend fun getOrCreate(id: String, create: suspend () -> PluginInstallation): PluginInstallation {
        suspend fun createInTransaction(): PluginInstallation {
            repository.findInTransaction(id)?.let { return it.toRuntime() }
            val installation = create()
            check(installation.id == id)
            if (repository.insertIfAbsentInTransaction(installation.toRecord())) return installation
            return checkNotNull(repository.findInTransaction(id)).toRuntime()
        }
        // Runtime callers already hold their mutex. Legacy writers release this transaction before
        // entering the runtime, so no database-lock -> runtime-mutex inversion is possible.
        return if (id == CONNECTIVITY_PLUGIN_ID) connectivitySettingsLock.withLock { createInTransaction() }
        else suspendTransaction(database) { createInTransaction() }
    }

    override suspend fun save(installation: PluginInstallation, expectedRevision: Long): Boolean =
        repository.save(installation.toRecord(), expectedRevision)

    private fun PluginInstallationRecord.toRuntime() = PluginInstallation(
        id, version, sha256, source, installed, desiredEnabled,
        approvedPermissions, configuration, updatedAt, revision, disableGeneration,
    )

    private fun PluginInstallation.toRecord() = PluginInstallationRecord(
        id, version, sha256, source, installed, desiredEnabled,
        approvedPermissions, configuration, updatedAt, revision, disableGeneration,
    )
}
