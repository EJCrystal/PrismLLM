package dev.soven.llmgateway.resilience

/**
 * 冷却状态持久化端口。写透队列只依赖这两个操作，便于替换存储与验证失败处理。
 */
interface CooldownStateStore {
    suspend fun upsert(snapshot: CooldownStateSnapshot)

    suspend fun delete(stateKey: String)

    /** 批量删除状态键。默认逐个调用 [delete]，各实现可使用批量 SQL 提高性能并避免串行超时。 */
    suspend fun deleteBatch(stateKeys: Collection<String>) {
        for (key in stateKeys) delete(key)
    }
}
