package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.domain.RouteCandidate
import dev.soven.llmgateway.repo.ChannelRepository
import dev.soven.llmgateway.security.CredentialCipher
import io.ktor.client.HttpClient
import org.slf4j.LoggerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 启用渠道的上游模型目录。用于未建别名时的 `/v1/models` 展示和直连路由，带短 TTL 缓存。
 *
 * @author 文杰
 * @since 2026-10-22
 */
class UpstreamModelCatalog(
    private val channelRepository: ChannelRepository,
    private val credentialCipher: CredentialCipher,
    private val httpClient: HttpClient,
    private val ttlMs: Long = 60_000,
    private val failureRetryMs: Long = 5_000,
) {
    private data class CacheEntry(val models: List<String>, val expiresAt: Long)

    private val logger = LoggerFactory.getLogger(UpstreamModelCatalog::class.java)
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun listEnabledModels(): List<String> = coroutineScope {
        enabledChannels()
            .map { channel -> async { modelsFor(channel) } }
            .awaitAll()
            .flatten()
            .distinct()
            .sorted()
    }

    suspend fun findCandidates(model: String, protocol: GatewayProtocol?): List<RouteCandidate> = coroutineScope {
        enabledChannels()
            .filter { protocol == null || protocol in it.protocols }
            .map { channel ->
                async {
                    if (model in modelsFor(channel)) {
                        RouteCandidate(channel = channel, upstreamModel = model, position = 0)
                    } else {
                        null
                    }
                }
            }
            .awaitAll()
            .filterNotNull()
    }

    private suspend fun enabledChannels(): List<ChannelRecord> =
        channelRepository.list().filter { it.enabled }

    private suspend fun modelsFor(channel: ChannelRecord): List<String> {
        val now = System.currentTimeMillis()
        cache[channel.id]?.takeIf { it.expiresAt > now }?.let { return it.models }

        val lock = locks.computeIfAbsent(channel.id) { Mutex() }
        return lock.withLock {
            val current = System.currentTimeMillis()
            cache[channel.id]?.takeIf { it.expiresAt > current }?.let { return@withLock it.models }
            val models = try {
                fetchUpstreamModels(channel, credentialCipher, httpClient).map { it.id }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn("failed to fetch models for channel {}", channel.id, e)
                val retryAt = current + failureRetryMs
                val previous = cache[channel.id]?.models
                if (previous != null) {
                    cache[channel.id] = CacheEntry(previous, retryAt)
                    return@withLock previous
                }
                cache[channel.id] = CacheEntry(emptyList(), retryAt)
                return@withLock emptyList()
            }
            cache[channel.id] = CacheEntry(models, current + ttlMs)
            models
        }
    }
}
