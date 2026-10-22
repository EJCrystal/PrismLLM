package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.domain.RouteCandidate
import dev.soven.llmgateway.repo.ChannelRepository
import dev.soven.llmgateway.security.CredentialCipher
import io.ktor.client.HttpClient
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
) {
    private data class CacheEntry(val models: List<String>, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun listEnabledModels(): List<String> =
        enabledChannels().flatMap { modelsFor(it) }.distinct().sorted()

    suspend fun findCandidates(model: String): List<RouteCandidate> =
        enabledChannels().mapNotNull { channel ->
            if (model in modelsFor(channel)) {
                RouteCandidate(channel = channel, upstreamModel = model, position = 0)
            } else {
                null
            }
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
            val models = runCatching {
                fetchUpstreamModels(channel, credentialCipher, httpClient).map { it.id }
            }.getOrDefault(emptyList())
            cache[channel.id] = CacheEntry(models, current + ttlMs)
            models
        }
    }
}
