package dev.soven.llmgateway.quota

import dev.soven.llmgateway.shared.error.GatewayError
import dev.soven.llmgateway.domain.ClientKeyRecord
import dev.soven.llmgateway.quota.domain.ClientKeyQuotaUsage
import dev.soven.llmgateway.quota.domain.checkClientKeyQuota
import dev.soven.llmgateway.quota.domain.dailyQuotaPeriod
import dev.soven.llmgateway.repo.ClientKeyQuotaRepository
import dev.soven.llmgateway.repo.StoredQuotaUsage
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * 调用方密钥额度检查与结算。每日用量持久化；并发计数仅在单实例内持有，进程退出即释放。
 */
class ClientKeyQuotaService(
    private val usageReader: suspend (String, Long) -> StoredQuotaUsage,
    private val usageWriter: suspend (String, Long, Long, Map<String, BigDecimal>) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(
        repository: ClientKeyQuotaRepository,
        now: () -> Long = System::currentTimeMillis,
    ) : this(repository::usage, repository::addUsage, now)

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
                ClientKeyQuotaUsage(usage.tokens, usage.costs, active),
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

    suspend fun settle(lease: QuotaLease, tokens: Long, costs: Map<String, BigDecimal>) {
        try {
            if (tokens <= 0 && costs.values.all { it.signum() <= 0 }) return
            usageWriter(lease.clientKeyId, lease.period, tokens, costs)
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
        return ClientKeyQuotaUsage(stored.tokens, stored.costs, concurrentRequests[clientKeyId] ?: 0)
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
