package dev.soven.llmgateway.repo

import dev.soven.llmgateway.db.ChannelKeyOAuthAccounts
import dev.soven.llmgateway.db.ChannelKeyOAuthWindows
import dev.soven.llmgateway.domain.OAuthAccountPolicy
import dev.soven.llmgateway.domain.OAuthStoredWindowState
import dev.soven.llmgateway.domain.OAuthWindowSnapshot
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.upsert

/** 账号级调度状态的读写接口；写透队列只依赖这个接口。 */
interface OAuthWindowStore {
    suspend fun loadAll(limit: Int = 50_000): List<OAuthStoredWindowState>

    suspend fun upsert(snapshot: OAuthWindowSnapshot)
}

/**
 * 订阅账号级调度状态的写透持久化。内存态是权威来源，库里的行只用于重启恢复。
 *
 * @author 文杰
 * @since 2026-11-06
 */
class OAuthWindowRepository(private val database: Database) : OAuthWindowStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val hitsSerializer = ListSerializer(Long.serializer())

    /** 恢复全部账号状态，同时带回当前调度策略用于裁剪过期窗口。 */
    override suspend fun loadAll(limit: Int): List<OAuthStoredWindowState> = suspendTransaction(database) {
        ChannelKeyOAuthWindows
            .join(ChannelKeyOAuthAccounts, JoinType.INNER, ChannelKeyOAuthWindows.keyId, ChannelKeyOAuthAccounts.keyId)
            .selectAll()
            .orderBy(ChannelKeyOAuthWindows.updatedAt to SortOrder.ASC)
            .limit(limit.coerceIn(1, 100_000))
            .map { row ->
                OAuthStoredWindowState(
                    snapshot = OAuthWindowSnapshot(
                        keyId = row[ChannelKeyOAuthWindows.keyId],
                        hits = decodeHits(row[ChannelKeyOAuthWindows.hits]),
                        cooldownUntil = row[ChannelKeyOAuthWindows.cooldownUntil],
                        consecutiveRateLimits = row[ChannelKeyOAuthWindows.consecutiveRateLimits],
                        updatedAt = row[ChannelKeyOAuthWindows.updatedAt],
                    ),
                    policy = OAuthAccountPolicy(
                        windowLimit = row[ChannelKeyOAuthAccounts.windowLimit],
                        windowSeconds = row[ChannelKeyOAuthAccounts.windowSeconds],
                        maxConcurrentRequests = row[ChannelKeyOAuthAccounts.maxConcurrentRequests],
                    ),
                )
            }
    }

    override suspend fun upsert(snapshot: OAuthWindowSnapshot) {
        suspendTransaction(database) {
            ChannelKeyOAuthWindows.upsert(ChannelKeyOAuthWindows.keyId) {
                it[keyId] = snapshot.keyId
                it[hits] = encodeHits(snapshot.hits)
                it[cooldownUntil] = snapshot.cooldownUntil
                it[consecutiveRateLimits] = snapshot.consecutiveRateLimits
                it[updatedAt] = snapshot.updatedAt
            }
        }
    }

    private fun encodeHits(hits: List<Long>): String =
        json.encodeToString(hitsSerializer, hits.takeLast(MAX_PERSISTED_HITS))

    private fun decodeHits(value: String): List<Long> = runCatching {
        json.decodeFromString(hitsSerializer, value)
    }.getOrDefault(emptyList())
        .asSequence()
        .filter { it > 0 }
        .sorted()
        .toList()

    private companion object {
        /** 单行快照的硬上限，避免窗口配置异常时把整张表撑大。 */
        const val MAX_PERSISTED_HITS = 100_000
    }
}
