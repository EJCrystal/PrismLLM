package dev.soven.llmgateway.repo

import dev.soven.llmgateway.db.RetentionSettings
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.upsert

/** 持久化请求日志自动清理开关，管理面修改后重启仍然生效。 */
class RetentionSettingsRepository(private val database: Database) {
    suspend fun loadCleanupEnabled(): Boolean? = suspendTransaction(database) {
        RetentionSettings.selectAll()
            .where { RetentionSettings.id eq SINGLETON_ID }
            .singleOrNull()
            ?.get(RetentionSettings.cleanupEnabled)
    }

    suspend fun saveCleanupEnabled(enabled: Boolean) {
        suspendTransaction(database) {
            RetentionSettings.upsert(RetentionSettings.id) {
                it[id] = SINGLETON_ID
                it[cleanupEnabled] = enabled
                it[updatedAt] = System.currentTimeMillis()
            }
        }
    }

    private companion object {
        const val SINGLETON_ID = 1
    }
}
