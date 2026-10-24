package dev.soven.llmgateway.quota

import dev.soven.llmgateway.domain.ClientKeyRecord
import dev.soven.llmgateway.repo.TeamLimitRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.math.BigDecimal

/**
 * 统一请求准入与结算：先应用密钥/团队速率和团队并发，再应用密钥每日额度与并发。
 */
class RequestLimitService(
    private val clientKeyQuotaService: ClientKeyQuotaService,
    private val teamLimitReader: suspend (String) -> TeamLimitPolicy?,
    private val rateLimiter: RequestRateLimiter = RequestRateLimiter(),
) {
    constructor(
        clientKeyQuotaService: ClientKeyQuotaService,
        teamLimitRepository: TeamLimitRepository,
        rateLimiter: RequestRateLimiter = RequestRateLimiter(),
    ) : this(clientKeyQuotaService, teamLimitRepository::find, rateLimiter)

    suspend fun acquire(clientKey: ClientKeyRecord): RequestLimitLease {
        val teamPolicy = clientKey.team?.let { teamLimitReader(it) }
        val rateLease = rateLimiter.acquire(clientKey, teamPolicy)
        try {
            val quotaLease = clientKeyQuotaService.acquire(clientKey)
            return RequestLimitLease(rateLease, quotaLease)
        } catch (cause: Throwable) {
            withContext(NonCancellable) { rateLimiter.release(rateLease) }
            throw cause
        }
    }

    suspend fun settle(
        lease: RequestLimitLease,
        tokens: Long,
        costs: Map<String, BigDecimal>,
    ) {
        try {
            clientKeyQuotaService.settle(lease.quotaLease, tokens, costs)
        } finally {
            withContext(NonCancellable) { rateLimiter.release(lease.rateLease) }
        }
    }

    suspend fun release(lease: RequestLimitLease) = withContext(NonCancellable) {
        try {
            clientKeyQuotaService.release(lease.quotaLease)
        } finally {
            rateLimiter.release(lease.rateLease)
        }
    }

    suspend fun teamUsage(team: String): TeamLimitUsage = rateLimiter.currentTeamUsage(team)

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
