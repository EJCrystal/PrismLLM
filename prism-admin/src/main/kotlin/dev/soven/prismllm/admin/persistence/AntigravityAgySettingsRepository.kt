package dev.soven.prismllm.admin.persistence

import dev.soven.prismllm.admin.domain.AntigravityAgySettingsRecord
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.upsert

object AntigravityAgySettings : Table("antigravity_agy_settings") {
    val id = integer("id")
    val enabled = bool("enabled")
    val proxy = text("proxy")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

class AntigravityAgySettingsRepository(private val database: Database) {
    suspend fun load(): AntigravityAgySettingsRecord? = suspendTransaction(database) {
        AntigravityAgySettings.selectAll().where { AntigravityAgySettings.id eq 1 }.singleOrNull()?.let {
            AntigravityAgySettingsRecord(it[AntigravityAgySettings.enabled], it[AntigravityAgySettings.proxy],
                it[AntigravityAgySettings.updatedAt])
        }
    }

    suspend fun save(settings: AntigravityAgySettingsRecord): AntigravityAgySettingsRecord = suspendTransaction(database) {
        val record = settings.copy(updatedAt = System.currentTimeMillis())
        AntigravityAgySettings.upsert(AntigravityAgySettings.id) {
            it[id] = 1
            it[enabled] = record.enabled
            it[proxy] = record.proxy
            it[updatedAt] = record.updatedAt
        }
        record
    }
}
