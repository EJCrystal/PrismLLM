package dev.soven.llmgateway.quota

import dev.soven.llmgateway.domain.ClientKeyRecord
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
    ) {
        clientKeyQuotaService.settle(lease.quotaLease, tokens, costs)
    }

    suspend fun release(lease: RequestLimitLease) {
        clientKeyQuotaService.release(lease.quotaLease)
    }

    suspend fun clientKeyUsage(clientKeyId: String): ClientKeyQuotaUsage {
        val usage = clientKeyQuotaService.currentUsage(clientKeyId)
        return usage.copy(currentMinuteRequests = rateLimiter.currentKeyUsage(clientKeyId))
    }
}

data class RequestLimitLease(
    val rateLease: RequestRateLease,
    val quotaLease: QuotaLease,
) {
    val clientKeyId: String get() = quotaLease.clientKeyId
}
