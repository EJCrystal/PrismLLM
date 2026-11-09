package dev.soven.llmgateway.plugin.quota

import com.google.common.cache.CacheBuilder
import dev.soven.llmgateway.channel.domain.ChannelKeyRecord
import dev.soven.llmgateway.channel.domain.ChannelRecord
import dev.soven.llmgateway.channel.domain.ChannelQuotaSettings
import dev.soven.llmgateway.channel.domain.ChannelQuotaCredentialSource
import dev.soven.llmgateway.oauth.KeyCredentialResolver
import dev.soven.llmgateway.shared.crypto.CredentialCipher
import dev.soven.llmgateway.spi.quota.ChannelQuotaProvider
import dev.soven.llmgateway.spi.quota.ChannelQuotaQuery
import dev.soven.llmgateway.spi.quota.ChannelQuotaResult
import dev.soven.llmgateway.spi.quota.ChannelQuotaStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/** 手动查询专用：按渠道与目标密钥缓存 60 秒、合并相同查询、最多 4 个阻塞插件调用。 */
class ChannelQuotaService(
    private val registry: ChannelQuotaProviderRegistry,
    private val cipher: CredentialCipher,
    private val credentials: KeyCredentialResolver,
    private val now: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = 45_000,
) : AutoCloseable {
    private data class Cached(val channel: ChannelRecord, val result: ChannelQuotaSnapshot, val at: Long)
    private data class CacheKey(val channelId: String, val keyId: String?)
    private data class QueryAttribution(
        val source: ChannelQuotaCredentialSource? = null,
        val keys: List<ChannelQuotaKeyRef> = emptyList(),
    )
    private val cache = CacheBuilder.newBuilder().maximumSize(1024).build<CacheKey, Cached>()
    private val locks = Array(64) { Mutex() }
    private val executor = ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS, SynchronousQueue()) { work ->
        Thread(work, "channel-quota-query").apply { isDaemon = true }
    }

    suspend fun query(channel: ChannelRecord, keyId: String? = null): ChannelQuotaSnapshot {
        val key = keyId?.let { id -> requireNotNull(channel.keys.find { it.id == id }) { "channel key not found" } }
        val target = if (key == null) channel else channel.copy(
            keys = listOf(key), quota = key.quota ?: ChannelQuotaSettings(), quotaCredential = key.quotaCredential,
            // 其他密钥保存设置会改变父行时间戳，不应使当前账户的缓存失效。
            updatedAt = 0,
        )
        val cacheKey = CacheKey(channel.id, keyId)
        val attribution = AtomicReference(QueryAttribution())
        val cacheable = AtomicBoolean(true)
        val result = withTimeoutOrNull(timeoutMs) {
            locks[(cacheKey.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
                cache.getIfPresent(cacheKey)?.let { cached ->
                    if (cached.channel == target && now() - cached.at in 0 until 60_000) {
                        return@withLock cached.result.copy(cached = true)
                    }
                }
                val queried = when {
                    target.quota.providerId.isEmpty() -> snapshot(target, "DISABLED", if (keyId == null) "此渠道未启用额度查询" else "此密钥未启用额度查询，请先单独配置其所属账户")
                    else -> {
                        val provider = registry.find(target.quota.providerId)
                        if (provider == null) snapshot(target, "UNSUPPORTED", "额度插件未安装或加载失败，请检查插件目录并重启网关")
                        else execute(target, provider, attribution, keyId != null, cacheable)
                    }
                }
                val source = attribution.get()
                val result = queried.copy(queryKey = source.keys.lastOrNull(), attemptedKeys = source.keys, credentialSource = source.source,
                    scope = if (keyId == null) "CHANNEL" else "KEY", targetKeyId = keyId)
                if (cacheable.get()) cache.put(cacheKey, Cached(target, result, now()))
                result
            }
        } ?: run {
            cacheable.set(false)
            val source = attribution.get()
            snapshot(target, "ERROR", "额度查询超时，请稍后重试").copy(
                queryKey = source.keys.lastOrNull(),
                attemptedKeys = source.keys,
                credentialSource = source.source,
                scope = if (keyId == null) "CHANNEL" else "KEY",
                targetKeyId = keyId,
            )
        }
        return result
    }

    private suspend fun execute(
        channel: ChannelRecord,
        provider: ChannelQuotaProvider,
        attribution: AtomicReference<QueryAttribution>,
        specificKey: Boolean,
        cacheable: AtomicBoolean,
    ): ChannelQuotaSnapshot {
        // 凭据解析可能触发订阅账号刷新，必须在挂起上下文完成；阻塞线程只消费解析结果。
        val eligibleKeys = if (channel.quota.credentialSource == ChannelQuotaCredentialSource.DEDICATED) {
            emptyList()
        } else {
            quotaKeys(channel, specificKey)
        }
        val resolvedKeys = eligibleKeys.mapNotNull { key ->
            try {
                key to credentials.resolveCredential(key)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: VirtualMachineError) {
                throw cause
            } catch (cause: Throwable) {
                // 单个账号不可用时跳过，其他账号仍可查询。
                null
            }
        }
        return suspendCancellableCoroutine { continuation ->
            val task = FutureTask {
                val result = try {
                    queryWithCredential(channel, provider, attribution, eligibleKeys.size, resolvedKeys)
                } catch (cause: Throwable) {
                    // 插件异常可能包含凭据或完整上游响应，不返回也不记录其文本。
                    if (cause is VirtualMachineError) throw cause
                    snapshot(channel, "ERROR", "额度插件查询失败，请检查上游接口和插件配置")
                }
                continuation.resume(result)
            }
            continuation.invokeOnCancellation { task.cancel(true) }
            try {
                executor.execute(task)
            } catch (_: RejectedExecutionException) {
                cacheable.set(false)
                continuation.resume(snapshot(channel, "ERROR", "额度查询繁忙，请稍后重试"))
            }
        }
    }

    private fun quotaKeys(channel: ChannelRecord, specificKey: Boolean): List<ChannelKeyRecord> =
        // 明确选中的密钥可查询停用账户的余额，且不得切换到另一个账户。
        channel.keys.filter { it.credentialSet && (specificKey || it.isRoutable(now())) }
            .sortedWith(compareBy({ it.priority }, { it.name }, { it.id }))
            .take(if (specificKey) 1 else 2)

    private fun queryWithCredential(
        channel: ChannelRecord,
        provider: ChannelQuotaProvider,
        attribution: AtomicReference<QueryAttribution>,
        eligibleKeyCount: Int,
        resolvedKeys: List<Pair<ChannelKeyRecord, String>>,
    ): ChannelQuotaSnapshot {
        val secrets = mutableListOf<String>()
        fun query(credential: String): ChannelQuotaResult = provider.query(ChannelQuotaQuery(
            channelId = channel.id,
            baseUrl = channel.baseUrl,
            authType = channel.authType,
            credential = credential,
            configJson = channel.quota.config.toString(),
        ))
        val found = if (channel.quota.credentialSource == ChannelQuotaCredentialSource.DEDICATED) {
            val stored = channel.quotaCredential
                ?: return snapshot(channel, "ERROR", "尚未设置独立额度查询凭据，请先保存系统访问令牌")
            if (Thread.currentThread().isInterrupted) throw InterruptedException("quota query cancelled")
            val credential = cipher.decrypt(stored.ciphertext, stored.nonce)
            secrets += credential
            attribution.set(QueryAttribution(ChannelQuotaCredentialSource.DEDICATED))
            query(credential)
        } else {
            if (eligibleKeyCount == 0) return snapshot(channel, "ERROR", "没有可用于额度查询的启用密钥")
            if (resolvedKeys.isEmpty()) return snapshot(channel, "ERROR", "密钥凭据暂不可用，无法查询额度")
            var result: ChannelQuotaResult? = null
            for ((key, credential) in resolvedKeys) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("quota query cancelled")
                secrets += credential
                val keyRef = ChannelQuotaKeyRef(key.id, safeText(key.name, secrets, 120) ?: "未命名密钥")
                attribution.updateAndGet { QueryAttribution(ChannelQuotaCredentialSource.CHANNEL_KEY, it.keys + keyRef) }
                result = query(credential)
                if (result.status != ChannelQuotaStatus.AUTHENTICATION_FAILED) break
            }
            checkNotNull(result)
        }
        return ChannelQuotaSnapshot(
            channelId = channel.id,
            providerId = channel.quota.providerId,
            status = found.status.name,
            remaining = decimal(found.remaining),
            used = decimal(found.used),
            limit = decimal(found.limit),
            unit = safeText(found.unit, secrets, 40),
            planName = safeText(found.planName, secrets, 120),
            message = safeText(found.message, secrets, 300),
            checkedAt = now(),
            sharedKeyCount = channel.keys.size,
        )
    }

    private fun decimal(value: BigDecimal?): String? = value?.let {
        require(it.precision() <= 40 && it.scale() in -20..20) { "quota number out of range" }
        it.stripTrailingZeros().toPlainString()
    }

    private fun safeText(value: String?, secrets: List<String>, maxLength: Int): String? {
        var text = value ?: return null
        secrets.filter { it.isNotEmpty() }.forEach { text = text.replace(it, "[REDACTED]") }
        return text.filter { !it.isISOControl() }.take(maxLength).ifBlank { null }
    }

    private fun snapshot(channel: ChannelRecord, status: String, message: String) = ChannelQuotaSnapshot(
        channelId = channel.id,
        providerId = channel.quota.providerId,
        status = status,
        message = message,
        checkedAt = now(),
        sharedKeyCount = channel.keys.size,
    )

    override fun close() {
        executor.shutdownNow()
        cache.invalidateAll()
    }
}

@Serializable
data class ChannelQuotaSnapshot(
    val channelId: String,
    val providerId: String,
    val status: String,
    val scope: String = "CHANNEL",
    val remaining: String? = null,
    val used: String? = null,
    val limit: String? = null,
    val unit: String? = null,
    val planName: String? = null,
    val message: String? = null,
    val checkedAt: Long,
    val sharedKeyCount: Int,
    val cached: Boolean = false,
    /** 最后实际调用插件的密钥；无可用密钥或未执行查询时为 null。 */
    val queryKey: ChannelQuotaKeyRef? = null,
    /** 按调用顺序记录，鉴权失败回退时最多两项。 */
    val attemptedKeys: List<ChannelQuotaKeyRef> = emptyList(),
    /** 实际传入插件的凭据来源；未调用插件时为空。 */
    val credentialSource: ChannelQuotaCredentialSource? = null,
    /** 用户选定的绑定密钥；独立系统令牌查询也保留此归属，与实际模型查询密钥区分。 */
    val targetKeyId: String? = null,
)

/** 只返回渠道密钥的标识与名称，不包含上游凭据。 */
@Serializable
data class ChannelQuotaKeyRef(val id: String, val name: String)
