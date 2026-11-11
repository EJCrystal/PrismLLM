package dev.soven.prismllm.quota.infra

import dev.soven.prismllm.quota.domain.ClientKeyQuotaUsage
import dev.soven.prismllm.quota.domain.ClientKeyRecord
import dev.soven.prismllm.quota.domain.checkClientKeyQuota
import dev.soven.prismllm.quota.domain.dailyQuotaPeriod
import dev.soven.prismllm.shared.error.GatewayError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * 调用方密钥额度检查与结算。每日用量通过端口持久化；并发计数仅在单实例内持有，进程退出即释放。
 */
class ClientKeyQuotaService(
    private val usageReader: suspend (String, Long) -> ClientKeyQuotaUsage,
    private val usageWriter: suspend (String, Long, Long, BigDecimal) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val locks = Array(64) { Mutex() }
    private val concurrentRequests = ConcurrentHashMap<String, Int>()

    suspend fun acquire(clientKey: ClientKeyRecord): QuotaLease {
        val lock = lockFor(clientKey.id)
        return lock.withLock {
            val current = now()
            val period = dailyQuotaPeriod(current)
            val usage = usageReader(clientKey.id, period)
            val active = concurrentRequests[clientKey.id] ?: 0
            val result = checkClientKeyQuota(
                clientKey.quotas,
                ClientKeyQuotaUsage(usage.dailyTokens, usage.dailyUsdCost, active),
            )
            if (!result.allowed) {
                throw GatewayError(
                    HttpStatusCode.TooManyRequests,
                    result.reason?.code ?: "quota_exceeded",
                    result.message,
                    "rate_limit_error",
                )
            }
            concurrentRequests[clientKey.id] = active + 1
            QuotaLease(clientKey.id, period)
        }
    }

    suspend fun settle(lease: QuotaLease, tokens: Long, usdCost: BigDecimal) {
        try {
            if (tokens <= 0 && usdCost.signum() <= 0) return
            usageWriter(lease.clientKeyId, lease.period, tokens, usdCost)
        } finally {
            release(lease)
        }
    }

    fun release(lease: QuotaLease) {
        concurrentRequests.compute(lease.clientKeyId) { _, active ->
            val next = (active ?: 0) - 1
            if (next > 0) next else null
        }
    }

    suspend fun currentUsage(clientKeyId: String): ClientKeyQuotaUsage {
        val period = dailyQuotaPeriod(now())
        val stored = usageReader(clientKeyId, period)
        return stored.copy(concurrentRequests = concurrentRequests[clientKeyId] ?: 0)
    }

    /** 当前在途请求数快照，仅用于管理面展示；不读取数据库。 */
    fun activeRequestCounts(): Map<String, Int> =
        concurrentRequests.entries
            .mapNotNull { (clientKeyId, count) -> count.takeIf { it > 0 }?.let { clientKeyId to it } }
            .toMap()

    private fun lockFor(clientKeyId: String): Mutex =
        locks[Math.floorMod(clientKeyId.hashCode(), locks.size)]
}

data class QuotaLease(val clientKeyId: String, val period: Long)
