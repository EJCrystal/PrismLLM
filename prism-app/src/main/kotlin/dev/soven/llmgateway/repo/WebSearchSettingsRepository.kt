package dev.soven.llmgateway.repo

import dev.soven.llmgateway.db.WebSearchSettings
import dev.soven.llmgateway.domain.WebSearchSettingsRecord
import dev.soven.llmgateway.shared.crypto.CredentialCipher
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.slf4j.LoggerFactory

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
    private val logger = LoggerFactory.getLogger(WebSearchSettingsRepository::class.java)

    /** 没有保存过设置时返回 null，调用方回退到环境变量默认值。 */
    suspend fun load(): WebSearchSettingsRecord? = suspendTransaction(database) {
        WebSearchSettings.selectAll()
            .where { WebSearchSettings.id eq SINGLETON_ID }
            .singleOrNull()
            ?.let { row ->
                val ciphertext = row[WebSearchSettings.apiKeyCiphertext]
                var unreadable = false
                val apiKey = ciphertext.takeIf { it.isNotEmpty() }?.let {
                    runCatching { credentialCipher.decrypt(it, row[WebSearchSettings.apiKeyNonce]) }
                        .onFailure {
                            // 主密钥轮换或密文损坏时不能让配置读取整体失败；管理员需要能进页面重填。
                            unreadable = true
                            logger.warn(
                                "web search api key cannot be decrypted; re-enter it in the admin UI ({})",
                                it::class.simpleName,
                            )
                        }
                        .getOrNull()
                }
                WebSearchSettingsRecord(
                    enabled = row[WebSearchSettings.enabled],
                    provider = row[WebSearchSettings.provider],
                    apiKey = apiKey,
                    baseUrl = row[WebSearchSettings.baseUrl],
                    maxResults = row[WebSearchSettings.maxResults],
                    timeoutMs = row[WebSearchSettings.timeoutMs],
                    maxRounds = row[WebSearchSettings.maxRounds],
                    maxPageChars = row[WebSearchSettings.maxPageChars],
                    updatedAt = row[WebSearchSettings.updatedAt],
                    apiKeyUnreadable = unreadable,
                )
            }
    }

    /**
     * @param preserveApiKey 为 true 时不更新密钥列，保留原密文（即使当前无法解密）。
     */
    suspend fun save(settings: WebSearchSettingsRecord, preserveApiKey: Boolean = false) {
        val encrypted = settings.apiKey?.takeIf { it.isNotBlank() }?.let(credentialCipher::encrypt)
        val updatedAt = nowMs()
        suspendTransaction(database) {
            WebSearchSettings.upsert(WebSearchSettings.id) {
                it[id] = SINGLETON_ID
                it[enabled] = settings.enabled
                it[provider] = settings.provider
                if (!preserveApiKey) {
                    it[apiKeyCiphertext] = encrypted?.ciphertext.orEmpty()
                    it[apiKeyNonce] = encrypted?.nonce.orEmpty()
                    it[apiKeyKeyVersion] = encrypted?.keyVersion ?: CredentialCipher.CURRENT_KEY_VERSION
                }
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
