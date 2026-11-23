package dev.soven.prismllm.api

import dev.soven.prismllm.admin.domain.ChannelEgressSettingsRecord
import dev.soven.prismllm.admin.persistence.ChannelEgressSettingsRepository
import dev.soven.prismllm.channel.domain.ChannelProxyHosts

/**
 * 渠道出口代理的生效放行名单：部署环境变量与管理台保存的主机取并集。
 *
 * 数据面读取易失缓存，管理台保存后立即更新，不需要重启网关；
 * 启动时从数据库恢复一次，避免第一笔保存前看不到已放行的主机。
 *
 * @author 文杰
 * @since 2026-11-23
 */
class ChannelProxyHostAllowlist(
    deploymentHosts: Collection<String>,
    private val repository: ChannelEgressSettingsRepository,
) {
    private val deploymentHosts = deploymentHosts.toList()

    @Volatile
    private var storedHosts: List<String> = emptyList()

    @Volatile
    private var storedUpdatedAt: Long = 0

    /** 数据面每个候选都会检查；预先合并，避免请求路径反复分配列表。 */
    @Volatile
    private var effectiveHosts: List<String> = deploymentHosts

    fun deploymentHosts(): List<String> = deploymentHosts

    fun storedHosts(): List<String> = storedHosts

    fun storedUpdatedAt(): Long? = storedUpdatedAt.takeIf { it > 0 }

    fun effectiveHosts(): List<String> = effectiveHosts

    fun allows(host: String?): Boolean = ChannelProxyHosts.matches(host, effectiveHosts)

    suspend fun refresh() {
        val record = repository.load() ?: ChannelEgressSettingsRecord(emptyList(), 0)
        apply(record)
    }

    suspend fun replace(hosts: List<String>): ChannelEgressSettingsRecord =
        repository.save(hosts).also(::apply)

    private fun apply(record: ChannelEgressSettingsRecord) {
        storedHosts = record.allowedHosts
        storedUpdatedAt = record.updatedAt
        effectiveHosts = (deploymentHosts + record.allowedHosts).distinct()
    }
}
