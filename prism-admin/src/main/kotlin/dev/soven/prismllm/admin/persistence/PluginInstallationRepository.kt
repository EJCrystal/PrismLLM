package dev.soven.prismllm.admin.persistence

import dev.soven.prismllm.admin.domain.PluginInstallationRecord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

object PluginInstallations : Table("plugin_installations") {
    val id = varchar("plugin_id", 80)
    val version = varchar("plugin_version", 80)
    val sha256 = varchar("package_sha256", 64)
    val packageSource = varchar("source", 120)
    val installed = bool("installed")
    val desiredEnabled = bool("desired_enabled")
    val approvedPermissionsJson = text("approved_permissions_json")
    val configurationJson = text("configuration_json")
    val revision = long("revision")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

/** 只保存期望状态和卸载决定，不把某个 JVM 的加载结果写成全局事实。 */
class PluginInstallationRepository(private val database: Database) {
    suspend fun list(): List<PluginInstallationRecord> = suspendTransaction(database) {
        PluginInstallations.selectAll().map(::toRecord)
    }

    suspend fun find(id: String): PluginInstallationRecord? = suspendTransaction(database) {
        PluginInstallations.selectAll().where { PluginInstallations.id eq id }.singleOrNull()?.let(::toRecord)
    }

    suspend fun insertIfAbsent(installation: PluginInstallationRecord): Boolean = suspendTransaction(database) {
        PluginInstallations.insertIgnore { write(it, installation) }.insertedCount == 1
    }

    suspend fun save(installation: PluginInstallationRecord, expectedRevision: Long): Boolean = suspendTransaction(database) {
        PluginInstallations.update({
            (PluginInstallations.id eq installation.id) and (PluginInstallations.revision eq expectedRevision)
        }) {
            write(it, installation.copy(revision = expectedRevision + 1))
        } == 1
    }

    private fun write(statement: UpdateBuilder<*>, item: PluginInstallationRecord) {
        statement[PluginInstallations.id] = item.id
        statement[PluginInstallations.version] = item.version
        statement[PluginInstallations.sha256] = item.sha256
        statement[PluginInstallations.packageSource] = item.source
        statement[PluginInstallations.installed] = item.installed
        statement[PluginInstallations.desiredEnabled] = item.desiredEnabled
        statement[PluginInstallations.approvedPermissionsJson] = Json.encodeToString(item.approvedPermissions)
        statement[PluginInstallations.configurationJson] = item.configuration.toString()
        statement[PluginInstallations.revision] = item.revision
        statement[PluginInstallations.updatedAt] = item.updatedAt
    }

    private fun toRecord(row: ResultRow) = PluginInstallationRecord(
        id = row[PluginInstallations.id],
        version = row[PluginInstallations.version],
        sha256 = row[PluginInstallations.sha256],
        source = row[PluginInstallations.packageSource],
        installed = row[PluginInstallations.installed],
        desiredEnabled = row[PluginInstallations.desiredEnabled],
        approvedPermissions = Json.decodeFromString<List<String>>(row[PluginInstallations.approvedPermissionsJson]),
        configuration = Json.decodeFromString<JsonObject>(row[PluginInstallations.configurationJson]),
        updatedAt = row[PluginInstallations.updatedAt],
        revision = row[PluginInstallations.revision],
    )
}
