package dev.soven.prismllm.resilience.persistence

import org.jetbrains.exposed.v1.core.Table

/**
 * 候选冷却状态快照。内存为准，持久化只为重启恢复。
 *
 * @author 文杰
 * @since 2026-10-25
 */
object CooldownStates : Table("cooldown_state") {
    val stateKey = varchar("state_key", 300)
    val consecutiveFailures = integer("consecutive_failures")
    val cooldownUntil = long("cooldown_until")
    val reason = varchar("reason", 40).nullable()
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(stateKey)
}