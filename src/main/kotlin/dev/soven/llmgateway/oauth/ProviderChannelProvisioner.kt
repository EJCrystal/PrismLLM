package dev.soven.llmgateway.oauth

import dev.soven.llmgateway.domain.ChannelKind
import dev.soven.llmgateway.domain.ChannelModelRecord
import dev.soven.llmgateway.domain.OAuthProvider
import dev.soven.llmgateway.domain.ProviderEndpoints
import dev.soven.llmgateway.repo.ChannelRepository
import dev.soven.llmgateway.repo.OAuthAccountRepository
import org.slf4j.LoggerFactory

/**
 * 订阅账号只服务官方端点，因此每个 provider 对应一条系统置备的隐藏渠道。
 * 置备器负责按需创建这条渠道，并把账号收敛到它上面。
 *
 * @author 文杰
 * @since 2026-11-06
 */
class ProviderChannelProvisioner(
    private val channels: ChannelRepository,
    private val accounts: OAuthAccountRepository,
) {
    private val logger = LoggerFactory.getLogger(ProviderChannelProvisioner::class.java)

    /** 找到或创建 provider 渠道；网关尚未实现该 provider 的上游协议时返回 null。 */
    suspend fun ensure(provider: OAuthProvider): String? {
        val endpoint = ProviderEndpoints.of(provider)
        if (!endpoint.servable) return null
        channels.findProviderChannel(provider)?.let { return it.id }
        val created = channels.create(
            name = endpoint.channelName,
            baseUrl = endpoint.baseUrl,
            keys = emptyList(),
            authType = endpoint.authType,
            protocols = endpoint.protocols,
            enabled = true,
            modelMappings = endpoint.models.map { ChannelModelRecord(upstreamModel = it) },
            priority = 1,
            kind = ChannelKind.PROVIDER,
            provider = provider,
        )
        logger.info(
            "provisioned provider channel, provider={}, channelId={}",
            provider.value,
            created.id,
        )
        return created.id
    }

    /** 启动时把历史账号挂到各自的 provider 渠道；幂等，已就位的不写库。 */
    suspend fun reconcile(): Int {
        var moved = 0
        for (account in accounts.listAll()) {
            val channelId = ensure(account.provider) ?: continue
            if (account.channelIds == setOf(channelId)) continue
            accounts.setChannels(account.id, listOf(channelId))
            moved++
        }
        if (moved > 0) {
            logger.info("moved {} subscription accounts onto provider channels", moved)
        }
        return moved
    }
}
