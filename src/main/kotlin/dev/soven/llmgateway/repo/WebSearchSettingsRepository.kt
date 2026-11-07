package dev.soven.llmgateway.repo

import dev.soven.llmgateway.db.WebSearchSettings
import dev.soven.llmgateway.domain.WebSearchSettingsRecord
import dev.soven.llmgateway.security.CredentialCipher
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * 持久化管理台的 Web 搜索设置。搜索 API 密钥与渠道凭据一样使用
 * AES-256-GCM 加密，读取时在服务端解密。
 *
 * @author 文杰
 * @since 2026-11-07
 */
class WebSearchSettingsRepository(
    private val database: Database,
    private val credentialCipher: CredentialCipher,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** 没有保存过设置时返回 null，调用方回退到环境变量默认值。 */
    suspend fun load(): WebSearchSettingsRecord? = suspendTransaction(database) {
        WebSearchSettings.selectAll()
            .where { WebSearchSettings.id eq SINGLETON_ID }
            .singleOrNull()
            ?.let { row ->
                val ciphertext = row[WebSearchSettings.apiKeyCiphertext]
                WebSearchSettingsRecord(
                    enabled = row[WebSearchSettings.enabled],
                    provider = row[WebSearchSettings.provider],
                    apiKey = ciphertext.takeIf { it.isNotEmpty() }?.let {
                        credentialCipher.decrypt(it, row[WebSearchSettings.apiKeyNonce])
                    },
                    baseUrl = row[WebSearchSettings.baseUrl],
                    maxResults = row[WebSearchSettings.maxResults],
                    timeoutMs = row[WebSearchSettings.timeoutMs],
                    maxRounds = row[WebSearchSettings.maxRounds],
                    maxPageChars = row[WebSearchSettings.maxPageChars],
                    updatedAt = row[WebSearchSettings.updatedAt],
                )
            }
    }

    suspend fun save(settings: WebSearchSettingsRecord) {
        val encrypted = settings.apiKey?.takeIf { it.isNotBlank() }?.let(credentialCipher::encrypt)
        val updatedAt = nowMs()
        suspendTransaction(database) {
            WebSearchSettings.upsert(WebSearchSettings.id) {
                it[id] = SINGLETON_ID
                it[enabled] = settings.enabled
                it[provider] = settings.provider
                it[apiKeyCiphertext] = encrypted?.ciphertext.orEmpty()
                it[apiKeyNonce] = encrypted?.nonce.orEmpty()
                it[apiKeyKeyVersion] = encrypted?.keyVersion ?: CredentialCipher.CURRENT_KEY_VERSION
                it[baseUrl] = settings.baseUrl
                it[maxResults] = settings.maxResults
                it[timeoutMs] = settings.timeoutMs
                it[maxRounds] = settings.maxRounds
                it[maxPageChars] = settings.maxPageChars
                it[WebSearchSettings.updatedAt] = updatedAt
            }
        }
    }

    companion object {
        private const val SINGLETON_ID = 1
    }
}
