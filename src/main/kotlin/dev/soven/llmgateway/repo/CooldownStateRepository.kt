package dev.soven.llmgateway.repo

import dev.soven.llmgateway.db.CooldownStates
import dev.soven.llmgateway.resilience.CooldownStateSnapshot
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * 冷却状态写透持久化。内存为准，库里的行只是重启恢复用的快照。
 *
 * @author 文杰
 * @since 2026-10-25
 */
class CooldownStateRepository(private val database: Database) {
    suspend fun upsert(snapshot: CooldownStateSnapshot) {
        suspendTransaction(database) {
            CooldownStates.upsert(CooldownStates.stateKey) {
                it[stateKey] = snapshot.stateKey
                it[consecutiveFailures] = snapshot.consecutiveFailures
                it[cooldownUntil] = snapshot.cooldownUntil
                it[updatedAt] = System.currentTimeMillis()
            }
        }
    }

    suspend fun delete(stateKey: String) {
        suspendTransaction(database) {
            CooldownStates.deleteWhere { CooldownStates.stateKey eq stateKey }
        }
    }

    suspend fun loadActive(nowMs: Long): List<CooldownStateSnapshot> = suspendTransaction(database) {
        CooldownStates.selectAll()
            .where { CooldownStates.cooldownUntil greaterEq nowMs }
            .map(::toSnapshot)
    }

    private fun toSnapshot(row: ResultRow) = CooldownStateSnapshot(
        stateKey = row[CooldownStates.stateKey],
        consecutiveFailures = row[CooldownStates.consecutiveFailures],
        cooldownUntil = row[CooldownStates.cooldownUntil],
    )
}
