package dev.soven.llmgateway.quota

import dev.soven.llmgateway.api.GatewayException
import dev.soven.llmgateway.domain.ClientKeyRecord
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 按 UTC 固定分钟窗口限制密钥与团队请求数，并维护团队活动并发数。
 * 状态只在单实例内保存，分钟切换时旧窗口会被清理。
 */
class RequestRateLimiter(
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val keyWindows = mutableMapOf<String, WindowCounter>()
    private val teamWindows = mutableMapOf<String, WindowCounter>()
    private val activeTeamRequests = mutableMapOf<String, Int>()
    private var lastPrunedMinute: Long? = null

    suspend fun acquire(
        clientKey: ClientKeyRecord,
        teamPolicy: TeamLimitPolicy?,
    ): RequestRateLease = mutex.withLock {
        val minute = currentMinute(now())
        pruneExpiredWindows(minute)
        val keyWindow = keyWindows.getOrPut(clientKey.id) { WindowCounter(minute) }
        clientKey.quotas.requestsPerMinute?.let { limit ->
            if (keyWindow.requests >= limit) {
                reject("key_rate_limit_exceeded", "密钥每分钟请求数已达上限")
            }
        }

        val team = clientKey.team
        val teamWindow = team?.let { teamWindows.getOrPut(it) { WindowCounter(minute) } }
        val activeTeam = team?.let { activeTeamRequests[it] ?: 0 } ?: 0
        if (team != null && teamPolicy != null) {
            teamPolicy.requestsPerMinute?.let { limit ->
                if (teamWindow!!.requests >= limit) {
                    reject("team_rate_limit_exceeded", "团队每分钟请求数已达上限")
                }
            }
            teamPolicy.maxConcurrentRequests?.let { limit ->
                if (activeTeam >= limit) {
                    reject("team_concurrency_limit_exceeded", "团队并发请求数已达上限")
                }
            }
        }

        keyWindow.requests += 1
        if (team != null) {
            teamWindow!!.requests += 1
            activeTeamRequests[team] = activeTeam + 1
        }
        RequestRateLease(clientKeyId = clientKey.id, team = team, minute = minute)
    }

    suspend fun release(lease: RequestRateLease) {
        val team = lease.team ?: return
        mutex.withLock {
            activeTeamRequests.compute(team) { _, active ->
                val next = (active ?: 0) - 1
                if (next > 0) next else null
            }
        }
    }

    suspend fun currentTeamUsage(team: String): TeamLimitUsage = mutex.withLock {
        val minute = currentMinute(now())
        pruneExpiredWindows(minute)
        TeamLimitUsage(
            currentMinuteRequests = teamWindows[team]?.requests ?: 0,
            activeConcurrentRequests = activeTeamRequests[team] ?: 0,
        )
    }

    suspend fun currentKeyUsage(clientKeyId: String): Int = mutex.withLock {
        val minute = currentMinute(now())
        pruneExpiredWindows(minute)
        keyWindows[clientKeyId]?.requests ?: 0
    }

    private fun pruneExpiredWindows(minute: Long) {
        if (lastPrunedMinute == minute) return
        keyWindows.entries.removeIf { it.value.minute != minute }
        teamWindows.entries.removeIf { it.value.minute != minute }
        lastPrunedMinute = minute
    }

    private fun reject(code: String, message: String): Nothing =
        throw GatewayException(
            HttpStatusCode.TooManyRequests,
            code,
            message,
            "rate_limit_error",
        )

    private data class WindowCounter(
        val minute: Long,
        var requests: Int = 0,
    )
}

data class RequestRateLease(
    val clientKeyId: String,
    val team: String?,
    val minute: Long,
)

fun currentMinute(now: Long): Long = now / 60_000L
