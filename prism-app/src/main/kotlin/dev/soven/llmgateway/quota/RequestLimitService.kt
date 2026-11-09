package dev.soven.llmgateway.quota

import dev.soven.llmgateway.domain.ClientKeyRecord
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.math.BigDecimal

/**
 * 统一请求准入与结算：先应用密钥速率限制，再应用密钥每日额度与并发。
 */
class RequestLimitService(
    private val clientKeyQuotaService: ClientKeyQuotaService,
    private val rateLimiter: RequestRateLimiter = RequestRateLimiter(),
) {
    suspend fun acquire(clientKey: ClientKeyRecord): RequestLimitLease {
        val rateLease = rateLimiter.acquire(clientKey)
        try {
            val quotaLease = clientKeyQuotaService.acquire(clientKey)
            return RequestLimitLease(rateLease, quotaLease)
        } catch (cause: Throwable) {
            throw cause
        }
    }

    suspend fun settle(
        lease: RequestLimitLease,
        tokens: Long,
        costs: Map<String, BigDecimal>,
    ) = withContext(NonCancellable) {
        clientKeyQuotaService.settle(lease.quotaLease, tokens, costs)
    }

    suspend fun release(lease: RequestLimitLease) = withContext(NonCancellable) {
        clientKeyQuotaService.release(lease.quotaLease)
    }

    suspend fun clientKeyUsage(clientKeyId: String): ClientKeyQuotaUsage {
        val usage = clientKeyQuotaService.currentUsage(clientKeyId)
        return usage.copy(currentMinuteRequests = rateLimiter.currentKeyUsage(clientKeyId))
    }

    /** 当前各调用方密钥的在途请求数快照，仅用于管理面展示。 */
    fun clientKeyOccupancy(): Map<String, Int> = clientKeyQuotaService.activeRequestCounts()
}

data class RequestLimitLease(
    val rateLease: RequestRateLease,
    val quotaLease: QuotaLease,
) {
    val clientKeyId: String get() = quotaLease.clientKeyId
}
