package dev.soven.prismllm.channel.persistence

import dev.soven.prismllm.channel.domain.ModelTestSettingsRecord
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.upsert

/** 持久化渠道模型连通性测试的全局默认提示词。 */
class ModelTestSettingsRepository(private val database: Database) {
    suspend fun load(): ModelTestSettingsRecord = suspendTransaction(database) { loadInTransaction() }

    fun loadInTransaction(): ModelTestSettingsRecord {
        requireTransaction()
        return ModelTestSettings.selectAll()
            .where { ModelTestSettings.id eq SINGLETON_ID }
            .singleOrNull()
            ?.let {
                ModelTestSettingsRecord(
                    defaultPrompt = it[ModelTestSettings.defaultPrompt],
                    updatedAt = it[ModelTestSettings.updatedAt],
                )
            }
            ?: ModelTestSettingsRecord(DEFAULT_PROMPT, 0)
    }

    suspend fun save(defaultPrompt: String) = suspendTransaction(database) { saveInTransaction(defaultPrompt) }

    fun saveInTransaction(defaultPrompt: String) {
        requireTransaction()
        ModelTestSettings.upsert(ModelTestSettings.id) {
            it[id] = SINGLETON_ID
            it[ModelTestSettings.defaultPrompt] = defaultPrompt
            it[updatedAt] = System.currentTimeMillis()
        }
    }

    private fun requireTransaction() {
        check(TransactionManager.current().db === database) { "model test settings transaction database mismatch" }
    }

    companion object {
        const val DEFAULT_PROMPT = "请只回复 OK"
        private const val SINGLETON_ID = 1
    }
}
