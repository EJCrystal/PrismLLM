package dev.soven.prismllm.requestlog.persistence

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.upsert

/** 持久化请求日志设置，管理面修改后重启仍然生效。 */
class RetentionSettingsRepository(private val database: Database) {
    @Volatile
    private var cachedRequestBodyLoggingEnabled = false

    suspend fun loadCleanupEnabled(): Boolean? = suspendTransaction(database) {
        RetentionSettings.selectAll()
            .where { RetentionSettings.id eq SINGLETON_ID }
            .singleOrNull()
            ?.get(RetentionSettings.cleanupEnabled)
    }

    suspend fun loadRequestBodyLoggingEnabled(): Boolean? = suspendTransaction(database) {
        RetentionSettings.selectAll()
            .where { RetentionSettings.id eq SINGLETON_ID }
            .singleOrNull()
            ?.get(RetentionSettings.requestBodyLoggingEnabled)
    }.also { cachedRequestBodyLoggingEnabled = it ?: false }

    fun isRequestBodyLoggingEnabled(): Boolean = cachedRequestBodyLoggingEnabled

    suspend fun saveSettings(cleanupEnabled: Boolean, requestBodyLoggingEnabled: Boolean) {
        suspendTransaction(database) {
            RetentionSettings.upsert(RetentionSettings.id) {
                it[id] = SINGLETON_ID
                it[RetentionSettings.cleanupEnabled] = cleanupEnabled
                it[RetentionSettings.requestBodyLoggingEnabled] = requestBodyLoggingEnabled
                it[updatedAt] = System.currentTimeMillis()
            }
        }
        cachedRequestBodyLoggingEnabled = requestBodyLoggingEnabled
    }

    private companion object {
        const val SINGLETON_ID = 1
    }
}
