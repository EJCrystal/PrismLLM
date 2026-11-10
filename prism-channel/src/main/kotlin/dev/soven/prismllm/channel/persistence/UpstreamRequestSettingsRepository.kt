package dev.soven.prismllm.channel.persistence

import dev.soven.prismllm.shared.domain.UpstreamRequestOverrides
import dev.soven.prismllm.channel.domain.UpstreamRequestSettingsRecord
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.upsert

/** Global settings are cached per process; a committed save publishes the next request's snapshot. */
class UpstreamRequestSettingsRepository(private val database: Database) {
    /** 同 ChannelRepository：请求覆盖配置允许出现已删除的键，读取时忽略。 */
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: UpstreamRequestSettingsRecord? = null
    private val updateMutex = Mutex()

    suspend fun load(): UpstreamRequestSettingsRecord {
        cached?.let { return it }
        return updateMutex.withLock {
            cached ?: suspendTransaction(database) {
                UpstreamRequestSettings.selectAll().where { UpstreamRequestSettings.id eq 1 }.singleOrNull()?.let {
                    UpstreamRequestSettingsRecord(
                        json.decodeFromString<UpstreamRequestOverrides>(it[UpstreamRequestSettings.overrides]),
                        it[UpstreamRequestSettings.updatedAt],
                    )
                } ?: UpstreamRequestSettingsRecord()
            }.also { cached = it }
        }
    }

    suspend fun save(overrides: UpstreamRequestOverrides): UpstreamRequestSettingsRecord = updateMutex.withLock {
        // Once a save starts, commit and publish together even if the HTTP client disconnects.
        withContext(NonCancellable) {
            val encoded = json.encodeToString(overrides)
            val record = suspendTransaction(database) {
                val now = System.currentTimeMillis()
                UpstreamRequestSettings.upsert(UpstreamRequestSettings.id) {
                    it[id] = 1
                    it[UpstreamRequestSettings.overrides] = encoded
                    it[updatedAt] = now
                }
                UpstreamRequestSettingsRecord(json.decodeFromString<UpstreamRequestOverrides>(encoded), now)
            }
            cached = record
            record
        }
    }
}
