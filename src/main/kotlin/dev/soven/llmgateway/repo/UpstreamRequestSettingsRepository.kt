package dev.soven.llmgateway.repo

import dev.soven.llmgateway.db.UpstreamRequestSettings
import dev.soven.llmgateway.domain.UpstreamRequestOverrides
import dev.soven.llmgateway.domain.UpstreamRequestSettingsRecord
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.upsert

/** Global settings are persisted and read once per logical upstream request. */
class UpstreamRequestSettingsRepository(private val database: Database) {
    suspend fun load(): UpstreamRequestSettingsRecord = suspendTransaction(database) {
        UpstreamRequestSettings.selectAll().where { UpstreamRequestSettings.id eq 1 }.singleOrNull()?.let {
            UpstreamRequestSettingsRecord(
                Json.decodeFromString<UpstreamRequestOverrides>(it[UpstreamRequestSettings.overrides]),
                it[UpstreamRequestSettings.updatedAt],
            )
        } ?: UpstreamRequestSettingsRecord()
    }

    suspend fun save(overrides: UpstreamRequestOverrides): UpstreamRequestSettingsRecord = suspendTransaction(database) {
        val now = System.currentTimeMillis()
        UpstreamRequestSettings.upsert(UpstreamRequestSettings.id) {
            it[id] = 1
            it[UpstreamRequestSettings.overrides] = Json.encodeToString(overrides)
            it[updatedAt] = now
        }
        UpstreamRequestSettingsRecord(overrides, now)
    }
}
