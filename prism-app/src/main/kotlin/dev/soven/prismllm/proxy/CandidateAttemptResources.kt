package dev.soven.prismllm.proxy

import dev.soven.prismllm.channel.domain.key.ChannelKeyRateLimitDecision
import dev.soven.prismllm.channel.domain.key.ChannelKeyRateLimitLease
import dev.soven.prismllm.channel.persistence.KeyProbeLease
import dev.soven.prismllm.oauth.infra.OAuthAccountDecision
import dev.soven.prismllm.oauth.infra.OAuthAccountLease
import dev.soven.prismllm.resilience.CooldownPermit
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 一个候选从准入到尝试的唯一资源所有者；RPM/账号窗口消耗不回滚。 */
internal class CandidateAttemptResources(
    private val releaseCooldown: (CooldownPermit) -> Unit,
    private val releaseRateLimit: suspend (ChannelKeyRateLimitLease?) -> Unit,
    private val releaseOAuth: suspend (OAuthAccountLease?) -> Unit,
    private val releaseProbe: suspend (KeyProbeLease) -> Unit,
    private val onReleaseFailure: (String, Throwable) -> Unit,
) {
    var cooldownPermit: CooldownPermit? = null
        private set
    var probeLease: KeyProbeLease? = null
        private set
    private var rateLimitLease: ChannelKeyRateLimitLease? = null
    private var oauthLease: OAuthAccountLease? = null
    private var cooldownSettled = false
    private var closed = false

    fun acquireCooldown(acquire: () -> CooldownPermit?): CooldownPermit? =
        acquire().also { cooldownPermit = it }

    suspend fun acquireRateLimit(acquire: suspend () -> ChannelKeyRateLimitDecision): ChannelKeyRateLimitDecision =
        acquireAndRegister(acquire) { decision ->
            if (decision is ChannelKeyRateLimitDecision.Allowed) rateLimitLease = decision.lease
        }

    suspend fun acquireOAuth(acquire: suspend () -> OAuthAccountDecision): OAuthAccountDecision =
        acquireAndRegister(acquire) { decision ->
            if (decision is OAuthAccountDecision.Allowed) oauthLease = decision.lease
        }

    suspend fun claimProbe(claim: suspend () -> KeyProbeLease?): KeyProbeLease? =
        acquireAndRegister(claim) { probeLease = it }

    fun <T> recordCooldown(record: (CooldownPermit) -> T): T =
        record(checkNotNull(cooldownPermit)).also { cooldownSettled = true }

    /** 仅 acquire + 登记不可取消；返回调用方后立即检查取消，绝不包住上游操作。 */
    private suspend fun <T> acquireAndRegister(acquire: suspend () -> T, register: (T) -> Unit): T {
        currentCoroutineContext().ensureActive()
        val result = withContext(NonCancellable) { acquire().also(register) }
        currentCoroutineContext().ensureActive()
        return result
    }

    suspend fun <T> use(block: suspend (CandidateAttemptResources) -> T): T = try {
        block(this)
    } finally {
        close()
    }

    private suspend fun close() {
        if (closed) return
        closed = true
        withContext(NonCancellable) {
            suspend fun release(resource: String, action: suspend () -> Unit) {
                try {
                    action()
                } catch (cause: Throwable) {
                    // 告警也不能覆盖原异常或阻止其它资源归还。
                    runCatching { onReleaseFailure(resource, cause) }
                }
            }
            probeLease?.let { lease -> release("probe") { releaseProbe(lease) } }
            release("oauth_concurrency") { releaseOAuth(oauthLease) }
            release("channel_key_concurrency") { releaseRateLimit(rateLimitLease) }
            if (!cooldownSettled) cooldownPermit?.let { permit ->
                release("cooldown") { releaseCooldown(permit) }
            }
        }
    }
}
