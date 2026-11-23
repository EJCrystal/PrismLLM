package dev.soven.prismllm.admin.persistence

import dev.soven.prismllm.admin.domain.ChannelEgressSettingsRecord
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory

/**
 * 持久化管理台放行的渠道出口代理主机。
 *
 * @author 文杰
 * @since 2026-11-23
 */
class ChannelEgressSettingsRepository(
    private val database: Database,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val logger = LoggerFactory.getLogger(ChannelEgressSettingsRepository::class.java)

    /** 没有保存过设置时返回 null，调用方只使用环境变量默认值。 */
    suspend fun load(): ChannelEgressSettingsRecord? = suspendTransaction(database) {
        ChannelEgressSettings.selectAll()
            .where { ChannelEgressSettings.id eq SINGLETON_ID }
            .singleOrNull()
            ?.let { row ->
                ChannelEgressSettingsRecord(
                    allowedHosts = decodeHosts(row[ChannelEgressSettings.allowedHosts]),
                    revision = row[ChannelEgressSettings.revision],
                    updatedAt = row[ChannelEgressSettings.updatedAt],
                )
            }
    }

    /**
     * 按版本条件保存。返回 null 表示 `expectedRevision` 与数据库当前版本不一致，
     * 调用方应提示重新读取；版本校验与写入在同一个事务和行锁内完成，跨实例也安全。
     */
    suspend fun save(allowedHosts: List<String>, expectedRevision: Long?): ChannelEgressSettingsRecord? =
        suspendTransaction(database) {
            val encoded = Json.encodeToString(allowedHosts)
            val updatedAt = nowMs()
            val current = ChannelEgressSettings.selectAll()
                .where { ChannelEgressSettings.id eq SINGLETON_ID }
                .forUpdate()
                .singleOrNull()
            if (current != null) {
                return@suspendTransaction updateLocked(current, allowedHosts, encoded, expectedRevision, updatedAt)
            }
            if (expectedRevision != null && expectedRevision != 0L) return@suspendTransaction null
            val inserted = ChannelEgressSettings.insertIgnore {
                it[id] = SINGLETON_ID
                it[ChannelEgressSettings.allowedHosts] = encoded
                it[ChannelEgressSettings.revision] = 1
                it[ChannelEgressSettings.updatedAt] = updatedAt
            }
            if (inserted.insertedCount > 0) {
                return@suspendTransaction ChannelEgressSettingsRecord(allowedHosts, 1, updatedAt)
            }
            // 另一个实例刚插入单行配置；重新加锁后按当前版本更新。
            val existing = ChannelEgressSettings.selectAll()
                .where { ChannelEgressSettings.id eq SINGLETON_ID }
                .forUpdate()
                .singleOrNull()
                ?: return@suspendTransaction null
            updateLocked(existing, allowedHosts, encoded, expectedRevision, updatedAt)
        }

    private fun updateLocked(
        current: ResultRow,
        allowedHosts: List<String>,
        encoded: String,
        expectedRevision: Long?,
        updatedAt: Long,
    ): ChannelEgressSettingsRecord? {
        val currentRevision = current[ChannelEgressSettings.revision]
        if (expectedRevision != null && expectedRevision != currentRevision) return null
        val revision = currentRevision + 1
        ChannelEgressSettings.update({ ChannelEgressSettings.id eq SINGLETON_ID }) {
            it[ChannelEgressSettings.allowedHosts] = encoded
            it[ChannelEgressSettings.revision] = revision
            it[ChannelEgressSettings.updatedAt] = updatedAt
        }
        return ChannelEgressSettingsRecord(allowedHosts, revision, updatedAt)
    }

    private fun decodeHosts(raw: String): List<String> = runCatching {
        Json.decodeFromString<List<String>>(raw)
    }.onFailure {
        // 手工改库或旧数据损坏时不能让网关启动失败；管理员重新保存即可覆盖。
        logger.warn("channel egress settings hosts cannot be decoded; fall back to empty list", it)
    }.getOrDefault(emptyList())

    private companion object {
        const val SINGLETON_ID = 1
    }
}
