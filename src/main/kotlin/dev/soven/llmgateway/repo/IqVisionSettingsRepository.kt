package dev.soven.llmgateway.repo

import dev.soven.llmgateway.db.IqVisionSettings
import dev.soven.llmgateway.domain.IqVisionConfig
import dev.soven.llmgateway.domain.IqVisionSettingsRecord
import dev.soven.llmgateway.domain.effective
import dev.soven.llmgateway.security.CredentialCipher
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.slf4j.LoggerFactory

/**
 * 持久化管理台的 IQ 视觉判分配置。视觉模型 API 密钥与渠道凭据一样使用
 * AES-256-GCM 加密，读取时在服务端解密。
 *
 * 判分器在专用的阻塞线程池中执行，因此额外提供同步读取入口，
 * 避免在 already-blocking 的判分线程里再起协程调度。
 *
 * @author 文杰
 * @since 2026-11-08
 */
class IqVisionSettingsRepository(
    private val database: Database,
    private val credentialCipher: CredentialCipher,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val logger = LoggerFactory.getLogger(IqVisionSettingsRepository::class.java)

    /** 没有保存过设置时返回 null，调用方回退到环境变量默认值。 */
    suspend fun load(): IqVisionSettingsRecord? = suspendTransaction(database) { read() }

    /**
     * 阻塞式读取，供判分器所在的阻塞线程池直接调用。
     *
     * @param defaults 环境变量默认值；读取失败时返回它，避免判分整体失败。
     */
    fun loadBlocking(defaults: IqVisionConfig): IqVisionConfig {
        val stored = runCatching { transaction(database) { read() } }
            .onFailure {
                // 读取失败时回退到部署默认值，不让一次数据库抖动把判分整体打挂。
                logger.warn(
                    "iq vision settings unavailable, falling back to environment defaults ({})",
                    it::class.simpleName,
                )
            }
            .getOrNull()
        return stored.effective(defaults)
    }

    suspend fun save(settings: IqVisionSettingsRecord, preserveApiKey: Boolean = false) {
        val encrypted = settings.apiKey?.takeIf { it.isNotBlank() }?.let(credentialCipher::encrypt)
        val updatedAt = nowMs()
        suspendTransaction(database) {
            IqVisionSettings.upsert(IqVisionSettings.id) {
                it[IqVisionSettings.id] = SINGLETON_ID
                it[enabled] = settings.enabled
                it[baseUrl] = settings.baseUrl
                it[model] = settings.model
                if (!preserveApiKey) {
                    it[apiKeyCiphertext] = encrypted?.ciphertext.orEmpty()
                    it[apiKeyNonce] = encrypted?.nonce.orEmpty()
                    it[apiKeyKeyVersion] = encrypted?.keyVersion ?: CredentialCipher.CURRENT_KEY_VERSION
                }
                it[IqVisionSettings.visionTimeoutMs] = settings.timeoutMs
                it[IqVisionSettings.updatedAt] = updatedAt
            }
        }
    }

    private fun read(): IqVisionSettingsRecord? = IqVisionSettings.selectAll()
        .where { IqVisionSettings.id eq SINGLETON_ID }
        .singleOrNull()
        ?.let { row ->
            val ciphertext = row[IqVisionSettings.apiKeyCiphertext]
            var unreadable = false
            val apiKey = ciphertext.takeIf { it.isNotEmpty() }?.let {
                runCatching { credentialCipher.decrypt(it, row[IqVisionSettings.apiKeyNonce]) }
                    .onFailure {
                        // 主密钥轮换或密文损坏时不能让配置读取整体失败；管理员需要能进页面重填。
                        unreadable = true
                        logger.warn(
                            "iq vision api key cannot be decrypted; re-enter it in the admin UI ({})",
                            it::class.simpleName,
                        )
                    }
                    .getOrNull()
            }
            IqVisionSettingsRecord(
                enabled = row[IqVisionSettings.enabled],
                baseUrl = row[IqVisionSettings.baseUrl],
                model = row[IqVisionSettings.model],
                apiKey = apiKey,
                timeoutMs = row[IqVisionSettings.visionTimeoutMs],
                updatedAt = row[IqVisionSettings.updatedAt],
                apiKeyUnreadable = unreadable,
            )
        }

    private companion object {
        const val SINGLETON_ID = 1
    }
}
