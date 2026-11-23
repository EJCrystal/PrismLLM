package dev.soven.prismllm.admin.persistence

import dev.soven.prismllm.admin.domain.SystemPromptSettingsRecord
import dev.soven.prismllm.admin.domain.encodeForStorage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.upsert

object SystemPromptSettings : Table("system_prompt_settings") {
    val id = integer("id")
    val settings = text("settings")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

class SystemPromptSettingsRepository(
    private val database: Database,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val updateMutex = Mutex()

    @Volatile
    private var cached: SystemPromptSettingsRecord? = null

    suspend fun load(): SystemPromptSettingsRecord {
        cached?.let { return it }
        return updateMutex.withLock {
            cached ?: suspendTransaction(database) {
                SystemPromptSettings.selectAll().where { SystemPromptSettings.id eq 1 }.singleOrNull()?.let { row ->
                    json.decodeFromString<SystemPromptSettingsRecord>(row[SystemPromptSettings.settings])
                        .copy(updatedAt = row[SystemPromptSettings.updatedAt])
                } ?: SystemPromptSettingsRecord()
            }.also { cached = it }
        }
    }

    suspend fun save(settings: SystemPromptSettingsRecord): SystemPromptSettingsRecord = updateMutex.withLock {
        withContext(NonCancellable) {
            val encoded = settings.encodeForStorage()
            val record = suspendTransaction(database) {
                val updatedAt = nowMs()
                SystemPromptSettings.upsert(SystemPromptSettings.id) {
                    it[id] = 1
                    it[SystemPromptSettings.settings] = encoded
                    it[SystemPromptSettings.updatedAt] = updatedAt
                }
                json.decodeFromString<SystemPromptSettingsRecord>(encoded).copy(updatedAt = updatedAt)
            }
            cached = record
            record
        }
    }
}
