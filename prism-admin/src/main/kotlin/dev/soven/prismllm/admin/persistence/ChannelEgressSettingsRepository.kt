package dev.soven.prismllm.admin.persistence

import dev.soven.prismllm.admin.domain.ChannelEgressSettingsRecord
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.upsert
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
                    updatedAt = row[ChannelEgressSettings.updatedAt],
                )
            }
    }

    suspend fun save(allowedHosts: List<String>): ChannelEgressSettingsRecord = suspendTransaction(database) {
        val updatedAt = nowMs()
        val encoded = Json.encodeToString(allowedHosts)
        ChannelEgressSettings.upsert(ChannelEgressSettings.id) {
            it[id] = SINGLETON_ID
            it[ChannelEgressSettings.allowedHosts] = encoded
            it[ChannelEgressSettings.updatedAt] = updatedAt
        }
        ChannelEgressSettingsRecord(allowedHosts, updatedAt)
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
