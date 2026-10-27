package dev.soven.llmgateway.repo

import dev.soven.llmgateway.db.ModelTestSettings
import dev.soven.llmgateway.domain.ModelTestSettingsRecord
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.upsert

/** 持久化渠道模型连通性测试的全局默认提示词与推理强度。 */
class ModelTestSettingsRepository(private val database: Database) {
    suspend fun load(): ModelTestSettingsRecord = suspendTransaction(database) {
        ModelTestSettings.selectAll()
            .where { ModelTestSettings.id eq SINGLETON_ID }
            .singleOrNull()
            ?.let {
                ModelTestSettingsRecord(
                    defaultPrompt = it[ModelTestSettings.defaultPrompt],
                    defaultReasoningEffort = it[ModelTestSettings.defaultReasoningEffort],
                    updatedAt = it[ModelTestSettings.updatedAt],
                )
            }
            ?: ModelTestSettingsRecord(DEFAULT_PROMPT, null, 0)
    }

    suspend fun save(defaultPrompt: String, defaultReasoningEffort: String?) {
        suspendTransaction(database) {
            ModelTestSettings.upsert(ModelTestSettings.id) {
                it[id] = SINGLETON_ID
                it[ModelTestSettings.defaultPrompt] = defaultPrompt
                it[ModelTestSettings.defaultReasoningEffort] = defaultReasoningEffort
                it[updatedAt] = System.currentTimeMillis()
            }
        }
    }

    companion object {
        const val DEFAULT_PROMPT = "请只回复 OK"
        private const val SINGLETON_ID = 1
    }
}
