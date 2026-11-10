package dev.soven.prismllm.oauth

import dev.soven.prismllm.channel.domain.ChannelKind
import dev.soven.prismllm.channel.domain.ChannelModelRecord
import dev.soven.prismllm.shared.domain.OAuthProvider
import dev.soven.prismllm.channel.domain.ProviderEndpoints
import dev.soven.prismllm.channel.persistence.ChannelRepository
import dev.soven.prismllm.oauth.persistence.OAuthAccountRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** 单实例部署下串行化「查不到就创建」，避免并发首次导入各建一条渠道。 */
    private val ensureMutex = Mutex()

    /** 找到或创建 provider 渠道；网关尚未实现该 provider 的上游协议时返回 null。 */
    suspend fun ensure(provider: OAuthProvider): String? = ensureMutex.withLock {
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

    /**
     * 把各账号自己的模型目录合并回系统渠道。渠道模型是所有账号目录的并集，
     * 供路由前判断模型是否可能存在；账号级 supports() 再按自己的目录过滤。
     *
     * 仍有账号未同步目录时保留渠道原有映射，避免把未同步账号能调度的模型摘掉；
     * 全部账号同步后渠道目录收敛为并集，上游已下线的模型会随之移除。
     */
    suspend fun syncModelUnion(provider: OAuthProvider): Int {
        val channelId = ensure(provider) ?: return 0
        val providerAccounts = accounts.listAll().filter { it.provider == provider }
        val union = LinkedHashMap<String, ChannelModelRecord>()
        if (providerAccounts.any { it.modelCatalog == null }) {
            channels.find(channelId)?.modelMappings?.forEach { union[it.upstreamModel] = it }
        }
        providerAccounts.forEach { account ->
            account.modelCatalog?.forEach { entry ->
                union.putIfAbsent(
                    entry.upstreamModel,
                    ChannelModelRecord(
                        upstreamModel = entry.upstreamModel,
                        canonicalModelId = entry.canonicalModelId,
                    ),
                )
            }
        }
        if (union.isEmpty()) return 0
        channels.replaceModels(channelId, union.values.toList())
        return union.size
    }

    /**
     * 把 V54 标记为待搬迁的历史账号挂到各自的 provider 渠道。
     * 每个账号成功后清除标记，中途失败的下次启动继续；协议未实现的 provider 保持待搬迁。
     */
    suspend fun reconcile(): Int {
        var moved = 0
        val servable = OAuthProvider.entries.filter { ProviderEndpoints.of(it).servable }
        for (account in accounts.listPendingProviderChannel(servable)) {
            try {
                val channelId = ensure(account.provider) ?: continue
                if (account.channelIds != setOf(channelId)) {
                    accounts.setChannels(account.id, listOf(channelId))
                    moved++
                }
                accounts.markProviderChannelSettled(account.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 单个账号失败不阻塞其余账号；标记仍在，下次启动重试。
                logger.warn("failed to move subscription account onto provider channel, accountId={}", account.id, e)
            }
        }
        if (moved > 0) {
            logger.info("moved {} subscription accounts onto provider channels", moved)
        }
        return moved
    }
}
