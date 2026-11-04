package dev.soven.llmgateway.resilience

/**
 * 冷却状态持久化端口。写透队列只依赖这两个操作，便于替换存储与验证失败处理。
 */
interface CooldownStateStore {
    suspend fun upsert(snapshot: CooldownStateSnapshot)

    suspend fun delete(stateKey: String)
}
