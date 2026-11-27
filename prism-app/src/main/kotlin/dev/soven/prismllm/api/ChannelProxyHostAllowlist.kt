package dev.soven.prismllm.api

import dev.soven.prismllm.admin.domain.ChannelEgressSettingsRecord
import dev.soven.prismllm.admin.persistence.ChannelEgressSettingsRepository
import dev.soven.prismllm.channel.domain.ChannelProxyHosts
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 生效白名单的不可变快照；一次响应只读取一个引用，避免混用不同版本的字段。 */
data class ChannelProxyHostState(
    val storedHosts: List<String>,
    val effectiveHosts: List<String>,
    val revision: Long,
    val updatedAt: Long,
)

class ChannelEgressSettingsConflictException(
    val expectedRevision: Long?,
) : IllegalStateException("出口代理白名单已被其他管理员修改，请重新读取后再保存")

/**
 * 渠道出口代理的生效放行名单由管理台保存并持久化。
 *
 * 数据面读取不可变快照，管理台保存后立即更新，不需要重启网关；
 * 启动时从数据库恢复一次，避免第一笔保存前看不到已放行的主机。
 * 保存与刷新共用一把互斥锁，防止先完成的旧读取覆盖较新的缓存。
 *
 * @author 文杰
 * @since 2026-11-23
 */
class ChannelProxyHostAllowlist(
    private val repository: ChannelEgressSettingsRepository,
) {
    private val mutex = Mutex()

    @Volatile
    private var state = ChannelProxyHostState(
        storedHosts = emptyList(),
        effectiveHosts = emptyList(),
        revision = 0,
        updatedAt = 0,
    )

    fun snapshot(): ChannelProxyHostState = state

    fun allows(host: String?): Boolean = ChannelProxyHosts.matches(host, state.effectiveHosts)

    suspend fun refresh() {
        mutex.withLock {
            apply(repository.load() ?: ChannelEgressSettingsRecord(emptyList(), 0, 0))
        }
    }

    suspend fun replace(hosts: List<String>, expectedRevision: Long? = null): ChannelEgressSettingsRecord =
        mutex.withLock {
            val record = repository.save(hosts, expectedRevision)
                ?: throw ChannelEgressSettingsConflictException(expectedRevision)
            apply(record)
            record
        }

    private fun apply(record: ChannelEgressSettingsRecord) {
        state = ChannelProxyHostState(
            storedHosts = record.allowedHosts,
            effectiveHosts = record.allowedHosts,
            revision = record.revision,
            updatedAt = record.updatedAt,
        )
    }
}
