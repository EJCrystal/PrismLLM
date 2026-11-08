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

/** 一次待写入的配置；[preserveApiKey] 为 true 时保留库中现有密文不动。 */
data class IqVisionSettingsUpdate(
    val settings: IqVisionSettingsRecord,
    val preserveApiKey: Boolean = false,
)

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
    suspend fun load(): IqVisionSettingsRecord? = suspendTransaction(database) { read(lock = false) }

    /**
     * 阻塞式读取，供判分器所在的阻塞线程池直接调用。
     *
     * @param defaults 环境变量默认值；读取失败时返回它，避免判分整体失败。
     */
    fun loadBlocking(defaults: IqVisionConfig): IqVisionConfig {
        val stored = runCatching { transaction(database) { read(lock = false) } }
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

    /**
     * 在同一个事务里读取当前值、由 [apply] 计算新值并写回，返回保存后的值。
     *
     * 读和写必须原子：否则两个并发 PUT 会各自基于旧快照计算，后到的请求把端点改回自己
     * 看到的那一个，却带着另一个请求刚写入的密钥，下一次判分就把 A 的密钥发给了 B。
     * 单行表用行锁串行化，和 `KeyAutoDisableRepository.saveSettings` 的做法一致。
     *
     * [apply] 返回 null 表示放弃本次保存（校验失败），此时不写库。
     */
    suspend fun update(apply: (IqVisionSettingsRecord?) -> IqVisionSettingsUpdate?): IqVisionSettingsRecord? =
        suspendTransaction(database) {
            val current = read(lock = true)
            val update = apply(current) ?: return@suspendTransaction null
            write(update)
            read(lock = false)
        }

    private fun write(update: IqVisionSettingsUpdate) {
        val settings = update.settings
        val encrypted = settings.apiKey?.takeIf { it.isNotBlank() }?.let(credentialCipher::encrypt)
        val updatedAt = nowMs()
        IqVisionSettings.upsert(IqVisionSettings.id) {
            it[IqVisionSettings.id] = SINGLETON_ID
            it[enabled] = settings.enabled
            it[baseUrl] = settings.baseUrl
            it[model] = settings.model
            if (!update.preserveApiKey) {
                it[apiKeyCiphertext] = encrypted?.ciphertext.orEmpty()
                it[apiKeyNonce] = encrypted?.nonce.orEmpty()
                it[apiKeyKeyVersion] = encrypted?.keyVersion ?: CredentialCipher.CURRENT_KEY_VERSION
            }
            it[IqVisionSettings.visionTimeoutMs] = settings.timeoutMs
            it[IqVisionSettings.updatedAt] = updatedAt
        }
    }

    /**
     * @param lock 为 true 时对单行加 `for update`，把同一行的并发读改写串行化。
     *   行不存在时加不到锁，但那时也没有可被覆盖的密钥，两个并发插入各自 upsert 仍然安全。
     */
    private fun read(lock: Boolean): IqVisionSettingsRecord? {
        val query = IqVisionSettings.selectAll().where { IqVisionSettings.id eq SINGLETON_ID }
        if (lock) query.forUpdate()
        return query.singleOrNull()?.let { row ->
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
    }

    private companion object {
        const val SINGLETON_ID = 1
    }
}
