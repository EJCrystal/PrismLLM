package dev.soven.prismllm.oauth

import dev.soven.prismllm.channel.domain.ChannelKeyRecord
import dev.soven.prismllm.config.OAuthConfig
import dev.soven.prismllm.db.DiagnosticPreparationDeadline
import dev.soven.prismllm.oauth.domain.OAuthAccessToken
import dev.soven.prismllm.oauth.domain.OAuthAccountRecord
import dev.soven.prismllm.oauth.domain.OAuthAuthState
import dev.soven.prismllm.oauth.infra.OAuthRefreshException
import dev.soven.prismllm.oauth.infra.OAuthTokenProvider
import dev.soven.prismllm.oauth.infra.OAuthTokenProviderRegistry
import dev.soven.prismllm.oauth.persistence.OAuthAccountRepository
import dev.soven.prismllm.oauth.persistence.OAuthRefreshContext
import dev.soven.prismllm.shared.crypto.CredentialCipher
import dev.soven.prismllm.shared.crypto.EncryptedCredential
import dev.soven.prismllm.shared.domain.KeyCredentialResolver
import dev.soven.prismllm.shared.domain.UpstreamCredential
import dev.soven.prismllm.shared.domain.saturatedAdd
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 订阅账号不可用的原因。 */
enum class OAuthUnavailableReason {
    /** refresh token 已失效，需要管理员重新导入或重新授权。 */
    REAUTH_REQUIRED,

    /** 该 provider 未在网关配置中启用。 */
    PROVIDER_NOT_CONFIGURED,

    /** 本次刷新失败，但账号仍可重试。 */
    REFRESH_FAILED,
}

/**
 * 订阅账号不可用。静态密钥不会抛出该异常，而是直接走解密路径。
 *
 * @author 文杰
 * @since 2026-11-06
 */
class OAuthTokenUnavailableException(
    val reason: OAuthUnavailableReason,
    message: String,
    val errorCode: String? = null,
) : RuntimeException(message)

/** 管理面手动刷新结果。 */
sealed interface OAuthRefreshResult {
    data class Refreshed(val account: OAuthAccountRecord) : OAuthRefreshResult

    data class Failed(val reason: OAuthUnavailableReason, val errorCode: String?) : OAuthRefreshResult

    data object NotOAuthAccount : OAuthRefreshResult
}

/** Access and the exact credential row that produced it; never print token-bearing state. */
internal class OAuthAccountAccess(val account: OAuthAccountRecord, val token: OAuthAccessToken) {
    override fun toString(): String = "OAuthAccountAccess(<redacted>)"
}

/** The exact credential row and egress used to obtain a diagnostic token. Never log token contents. */
internal class DiagnosticOAuthAccess internal constructor(
    private val account: OAuthAccountRecord,
    val accessToken: String,
    private val exchangeProxyUrl: String?,
) {
    fun matches(account: OAuthAccountRecord): Boolean =
        account.id == this.account.id && account.provider == this.account.provider &&
            account.authorizedAt == this.account.authorizedAt && account.authState == this.account.authState &&
            account.refreshTokenCiphertext == this.account.refreshTokenCiphertext &&
            account.refreshTokenNonce == this.account.refreshTokenNonce &&
            account.refreshTokenKeyVersion == this.account.refreshTokenKeyVersion &&
            account.accessTokenCiphertext == this.account.accessTokenCiphertext &&
            account.accessTokenNonce == this.account.accessTokenNonce &&
            account.accessTokenKeyVersion == this.account.accessTokenKeyVersion && account.proxyUrl == exchangeProxyUrl

    override fun toString(): String = "DiagnosticOAuthAccess(<redacted>)"
}

/**
 * 订阅账号 access token 的解析、刷新与后台轮换。
 *
 * 约束：
 * - 同一账号的刷新按 accountId 串行，避免并发刷新把 refresh token 轮换链打断。
 * - 刷新失败不修改账号启停状态；账号不可用由 [OAuthAccountRecord.authState] 表达。
 * - 后台任务只处理仍可自动刷新的账号，终态账号等待管理员重新导入。
 * - 日志只记录 accountId、provider 和错误类型，不记录任何 token。
 *
 * @author 文杰
 * @since 2026-11-06
 */
class OAuthTokenService(
    private val repository: OAuthAccountRepository,
    private val cipher: CredentialCipher,
    private val providers: OAuthTokenProviderRegistry,
    private val config: OAuthConfig = OAuthConfig(),
    private val now: () -> Long = System::currentTimeMillis,
) : KeyCredentialResolver, AutoCloseable {
    private val logger = LoggerFactory.getLogger(OAuthTokenService::class.java)
    private val worker = AtomicReference<Job?>(null)
    private val refreshLifecycleLock = Any()
    private val refreshSupervisor = SupervisorJob()
    private val refreshScope = CoroutineScope(Dispatchers.IO + refreshSupervisor)
    private val refreshTasks = mutableMapOf<String, RefreshTask>()
    private val pendingRefreshes = mutableMapOf<String, PendingRefresh>()
    // Guarded by refreshLifecycleLock; only admitted tasks register a mutex, until completion.
    private val accountLocks = mutableMapOf<String, Mutex>()
    private val rotations = linkedMapOf<String, ArrayDeque<Rotation>>()
    private val refreshPermits = Semaphore(MAX_CONCURRENT_DIAGNOSTIC_REFRESHES)
    private var refreshAdmissionClosed = false
    @Volatile private var stopPersistenceRetries = false

    /**
     * 返回可用的 access token。普通静态密钥返回 null；
     * OAuth 账号不可用时抛出 [OAuthTokenUnavailableException]，调用方应切换到其他候选。
     */
    suspend fun accessToken(accountId: String, forceRefresh: Boolean = false): OAuthAccessToken? =
        accessTokenWithSnapshot(accountId, forceRefresh)?.token

    internal suspend fun accessTokenWithSnapshot(accountId: String, forceRefresh: Boolean = false): OAuthAccountAccess? {
        val account = repository.find(accountId)
        if (account == null) {
            synchronized(refreshLifecycleLock) { pendingRefreshes.remove(accountId) }
            return null
        }
        discardReplacedPending(account)
        if (!forceRefresh) cachedAccessToken(account)?.let { return OAuthAccountAccess(account, it) }
        return try {
            val saved = awaitRefresh(account, force = forceRefresh).account
            OAuthAccountAccess(saved, requireAccessToken(saved))
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            if (!DiagnosticPreparationDeadline.isTimeout(cause)) throw cause
            throw OAuthTokenUnavailableException(OAuthUnavailableReason.REFRESH_FAILED, "订阅账号刷新失败", "refresh_failed")
        }
    }

    /** Caller cancellation stops waiting, but never interrupts an exchange that can rotate a token. */
    internal suspend fun accessTokenForDiagnostic(
        account: OAuthAccountRecord,
        preparation: DiagnosticPreparationDeadline.Budget,
    ): DiagnosticOAuthAccess {
        preparation.remainingMillis(currentCoroutineContext().job)
        if (account.authState != OAuthAuthState.ACTIVE) {
            throw OAuthTokenUnavailableException(OAuthUnavailableReason.REAUTH_REQUIRED, "订阅账号当前不可用")
        }
        if (account.provider !in providers.available()) {
            throw OAuthTokenUnavailableException(
                OAuthUnavailableReason.PROVIDER_NOT_CONFIGURED,
                "OAuth 提供方 ${account.provider.value} 未配置",
            )
        }
        // Manual diagnosis also permits ACTIVE accounts whose enabled setting is false.
        cachedAccessToken(account)?.let { return DiagnosticOAuthAccess(account, it.accessToken, account.proxyUrl) }
        return try {
            val outcome = awaitRefresh(account, preparation = preparation)
            val saved = outcome.account
            if (!acceptsSnapshot(account, saved) || !saved.hasAccessToken) throw credentialReplaced()
            DiagnosticOAuthAccess(saved, decryptAccess(saved).accessToken, saved.proxyUrl)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            if (DiagnosticPreparationDeadline.isTimeout(cause) || cause is OAuthTokenUnavailableException) throw cause
            throw OAuthTokenUnavailableException(OAuthUnavailableReason.REFRESH_FAILED, "订阅账号刷新失败", "refresh_failed")
        }
    }

    private class RefreshWaiter(
        val account: OAuthAccountRecord,
        val caller: Job,
        val preparation: DiagnosticPreparationDeadline.Budget?,
        val force: Boolean,
    ) {
        fun active(): Boolean {
            if (!caller.isActive) return false
            val budget = preparation ?: return true
            return runCatching { budget.remainingMillis(caller) }.isSuccess
        }
    }

    private data class RefreshOutcome(val account: OAuthAccountRecord, val exchanged: Boolean)

    private class RefreshTask {
        val waiters = mutableListOf<RefreshWaiter>()
        lateinit var work: Deferred<RefreshOutcome>
        val persistenceFailure = CompletableDeferred<OAuthTokenUnavailableException>()
        var exchangeStarted = false
        @Volatile var waitingForExchangePermit = false
    }

    private suspend fun awaitRefresh(
        account: OAuthAccountRecord,
        preparation: DiagnosticPreparationDeadline.Budget? = null,
        force: Boolean = false,
    ): RefreshOutcome {
        var expiredTokenRetries = 0

        suspend fun awaitLoop(): RefreshOutcome {
            while (true) {
                if (synchronized(refreshLifecycleLock) { (pendingRefreshes.keys + refreshTasks.keys).size >= MAX_PENDING_REFRESH_RESULTS }) {
                    prunePendingResults()
                }
                val waiter = RefreshWaiter(account, currentCoroutineContext().job, preparation, force)
                val (task, registered) = synchronized(refreshLifecycleLock) {
                    check(!refreshAdmissionClosed) { "OAuth refresh service is closing" }
                    val existing = refreshTasks[account.id]
                    // A cancelling task still owns this account and its admission slot until it exits.
                    if (existing != null && existing.work.isCancelled && !existing.work.isCompleted) {
                        return@synchronized existing to false
                    }
                    val selected = existing?.takeUnless { it.work.isCompleted } ?: RefreshTask().also { created ->
                        if (existing == null && (refreshTasks.size >= MAX_DIAGNOSTIC_REFRESH_TASKS ||
                                (account.id !in pendingRefreshes && (pendingRefreshes.keys + refreshTasks.keys).size >= MAX_PENDING_REFRESH_RESULTS))) {
                            throw OAuthTokenUnavailableException(OAuthUnavailableReason.REFRESH_FAILED, "账号刷新繁忙，请稍后重试", "refresh_busy")
                        }
                        val mutex = lockFor(account.id)
                        created.work = refreshScope.async(start = CoroutineStart.LAZY) { runRefresh(account.id, created, mutex) }
                        refreshTasks[account.id] = created
                        created.work.invokeOnCompletion { cause ->
                            synchronized(refreshLifecycleLock) {
                                if (refreshTasks[account.id] === created) {
                                    refreshTasks.remove(account.id)
                                    accountLocks.remove(account.id)
                                }
                            }
                            if (cause != null && cause !is CancellationException) {
                                logger.warn("oauth refresh failed, accountId={}, error={}", account.id, cause::class.simpleName)
                            }
                        }
                    }
                    selected.waiters += waiter
                    selected.work.start()
                    selected to true
                }
                if (!registered) {
                    try {
                        task.work.join()
                    } finally {
                        synchronized(refreshLifecycleLock) {
                            task.waiters.remove(waiter)
                            if (!task.exchangeStarted && task.waiters.none { it.active() }) task.work.cancel()
                        }
                    }
                    continue
                }
                val outcome = try {
                    if (preparation == null) {
                        select {
                            task.work.onAwait { it }
                            task.persistenceFailure.onAwait { throw it }
                        }
                    } else {
                        task.work.await()
                    }
                } finally {
                    synchronized(refreshLifecycleLock) {
                        task.waiters.remove(waiter)
                        if (!task.exchangeStarted && task.waiters.none { it.active() }) task.work.cancel()
                    }
                }
                // A forced caller may join a normal task which decided to return its cached token.
                // That is not a refresh; loop and join/start a task that must exchange instead.
                if (outcome.account.hasAccessToken && (outcome.account.accessTokenExpiresAt ?: 0) <= now()) {
                    if (expiredTokenRetries >= MAX_EXPIRED_TOKEN_RETRIES) throw expiredAccessToken()
                    expiredTokenRetries++
                    continue
                }
                if (!force || outcome.exchanged) return outcome
            }
        }

        val outcome = if (preparation == null) {
            withTimeoutOrNull(saturatedAdd(config.requestTimeoutMs, DIAGNOSTIC_REFRESH_DB_TIMEOUT_MS)) {
                awaitLoop()
            }
        } else {
            DiagnosticPreparationDeadline.within(preparation) { awaitLoop() }
        }
        if (outcome != null) return outcome
        if (preparation != null) throw DiagnosticPreparationDeadline.deadlineExceeded()
        if (expiredTokenRetries > 0) throw expiredAccessToken()
        throw OAuthTokenUnavailableException(
            OAuthUnavailableReason.REFRESH_FAILED,
            "账号刷新尚未完成，请稍后重试",
            "refresh_save_pending",
        )
    }

    private suspend fun runRefresh(accountId: String, task: RefreshTask, mutex: Mutex): RefreshOutcome =
        mutex.withLock {
            synchronized(refreshLifecycleLock) { pendingRefreshes[accountId] }?.let { retained ->
                task.exchangeStarted = true
                val saved = withContext(NonCancellable) { persistRefresh(retained, task) }
                // Rotation must be committed first, but an access token can expire while storage
                // is unavailable. Re-read/use the newly saved refresh token for a fresh exchange.
                if (saved.hasAccessToken && (saved.accessTokenExpiresAt ?: 0) > now()) {
                    return@withLock RefreshOutcome(saved, exchanged = true)
                }
                task.exchangeStarted = false
            }
            synchronized(refreshLifecycleLock) {
                if (pendingRefreshes.size >= MAX_PENDING_REFRESH_RESULTS) {
                    throw OAuthTokenUnavailableException(OAuthUnavailableReason.REFRESH_FAILED, "账号刷新保存繁忙，请稍后重试", "refresh_busy")
                }
            }
            val context = DiagnosticPreparationDeadline.withinSynchronous(DIAGNOSTIC_REFRESH_DB_TIMEOUT_MS) {
                repository.loadRefreshContext(accountId)
                    ?: throw OAuthTokenUnavailableException(OAuthUnavailableReason.REAUTH_REQUIRED, "订阅账号已删除")
            } ?: throw DiagnosticPreparationDeadline.deadlineExceeded()
            val latest = context.account
            val eligible = synchronized(refreshLifecycleLock) { eligibleWaiters(task, latest) }
            if (eligible.isEmpty()) throw credentialReplaced()
            if (eligible.none { it.force }) cachedRefreshOutcome(latest)?.let { return@withLock it }

            var exchangeContext = context
            task.waitingForExchangePermit = true
            val pending = try {
                refreshPermits.withPermit {
                    task.waitingForExchangePermit = false
                    // Preparation may have waited behind other exchanges. Re-read the exact row
                    // before checking waiter snapshots or choosing the credential and egress.
                    exchangeContext = DiagnosticPreparationDeadline.withinSynchronous(DIAGNOSTIC_REFRESH_DB_TIMEOUT_MS) {
                        repository.loadRefreshContext(accountId)
                            ?: throw OAuthTokenUnavailableException(OAuthUnavailableReason.REAUTH_REQUIRED, "订阅账号已删除")
                    } ?: throw DiagnosticPreparationDeadline.deadlineExceeded()
                    val current = exchangeContext.account
                    val prepared = prepareRefresh(exchangeContext)
                    synchronized(refreshLifecycleLock) {
                        val admitted = eligibleWaiters(task, current)
                        if (refreshAdmissionClosed || task.waiters.none { it.active() }) {
                            throw CancellationException("OAuth refresh admission ended before exchange")
                        }
                        if (admitted.isEmpty()) throw credentialReplaced()
                        // A force waiter can leave while the task is queued for an exchange permit.
                        if (admitted.none { it.force }) cachedRefreshOutcome(current)?.let { return@withLock it }
                        task.exchangeStarted = true
                    }
                    // The service deadline starts before provider work. Its result slot closes
                    // the cancellation/return race: once encryption succeeds, persist despite timer expiry.
                    withContext(NonCancellable) {
                        val completed = AtomicReference<PendingRefresh?>()
                        withTimeoutOrNull(config.requestTimeoutMs) {
                            completed.set(exchange(prepared))
                        }
                        completed.get() ?: throw OAuthRefreshException(false, "timeout", "OAuth 刷新超时")
                    }
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                // A pre-exchange reauthorization rejection is not a provider failure.
                if (!task.exchangeStarted) throw cause
                val failure = cause as? OAuthRefreshException
                    ?: OAuthRefreshException(false, "refresh_failed", "订阅账号刷新失败")
                // Failure writes, like successful save retries, must not hold an exchange permit.
                withContext(NonCancellable) {
                    withinRefreshDatabaseBudget {
                        recordRefreshFailure(exchangeContext.account, failure.errorCode, failure.terminal)
                    }
                }
                throw OAuthTokenUnavailableException(
                    if (failure.terminal) OAuthUnavailableReason.REAUTH_REQUIRED else OAuthUnavailableReason.REFRESH_FAILED,
                    failure.message ?: "订阅账号刷新失败",
                    failure.errorCode,
                )
            }
            // Keep the account mutex and encrypted result, but release the global exchange permit
            // before any save attempt or backoff. Save retries remain bounded by MAX_DIAGNOSTIC_REFRESH_TASKS.
            synchronized(refreshLifecycleLock) { pendingRefreshes[accountId] = pending }
            val saved = withContext(NonCancellable) { persistRefresh(pending, task) }
            RefreshOutcome(saved, exchanged = true)
        }

    private class PreparedRefresh(
        val context: OAuthRefreshContext,
        val provider: OAuthTokenProvider,
    )

    /** Only encrypted values survive between persistence attempts or in the completed task. */
    private class PendingRefresh(
        val context: OAuthRefreshContext,
        val accessToken: EncryptedCredential,
        val rotatedRefreshToken: EncryptedCredential?,
        val expiresAt: Long,
        val tokenType: String,
        val scope: String?,
    )

    private class RefreshWrite(val account: OAuthAccountRecord?)
    private class Rotation(val before: OAuthAccountRecord, val after: OAuthAccountRecord)

    private fun sameSnapshot(before: OAuthAccountRecord, after: OAuthAccountRecord): Boolean =
        DiagnosticOAuthAccess(before, "", before.proxyUrl).matches(after)

    private fun acceptsSnapshot(before: OAuthAccountRecord, after: OAuthAccountRecord): Boolean {
        if (sameSnapshot(before, after)) return true
        return synchronized(refreshLifecycleLock) {
            var current = before
            rotations[before.id]?.forEach { rotation ->
                if (sameSnapshot(current, rotation.before)) current = rotation.after
            }
            sameSnapshot(current, after)
        }
    }

    private fun rememberRotation(before: OAuthAccountRecord, after: OAuthAccountRecord) = synchronized(refreshLifecycleLock) {
        val history = rotations.getOrPut(before.id) { ArrayDeque() }
        history.addLast(Rotation(before, after))
        while (history.size > MAX_ROTATIONS_PER_ACCOUNT) history.removeFirst()
        while (rotations.size > MAX_ROTATION_ACCOUNTS) rotations.remove(rotations.keys.first())
    }

    private fun decryptAccess(account: OAuthAccountRecord) = OAuthAccessToken(
        cipher.decrypt(requireNotNull(account.accessTokenCiphertext), requireNotNull(account.accessTokenNonce)),
        requireNotNull(account.accessTokenExpiresAt), account.tokenType,
    )

    private fun credentialReplaced() = OAuthTokenUnavailableException(
        OAuthUnavailableReason.REFRESH_FAILED, "订阅账号凭据已被替换，请重试", "credential_replaced",
    )

    private fun expiredAccessToken() = OAuthTokenUnavailableException(
        OAuthUnavailableReason.REFRESH_FAILED,
        "提供方返回的访问令牌已过期，请重新授权或稍后重试",
        "access_token_expired",
    )

    /**
     * 数据面统一的凭据解析入口：OAuth 账号取 access token，普通密钥解密静态凭据。
     * OAuth 账号不可用时绝不回退到 refresh token，避免把长期凭据发给上游。
     *
     * 凭据类型在候选构建阶段已经确定：静态密钥直接解密，订阅账号按账号 id 读取
     * access token，不再通过 channel_keys 判断账号身份。
     */
    override suspend fun resolveCredential(credential: UpstreamCredential): String = when (credential) {
        is ChannelKeyRecord -> cipher.decrypt(credential.credentialCiphertext, credential.credentialNonce)
        is OAuthAccountRecord -> accessToken(credential.id)?.accessToken
            ?: throw OAuthTokenUnavailableException(OAuthUnavailableReason.REAUTH_REQUIRED, "订阅账号已删除")
        else -> throw IllegalStateException("未知的上游凭据类型: ${credential::class.qualifiedName}")
    }

    private fun requireAccessToken(account: OAuthAccountRecord): OAuthAccessToken {
        if (!account.hasAccessToken) throw credentialReplaced()
        if ((account.accessTokenExpiresAt ?: 0) <= now()) {
            throw OAuthTokenUnavailableException(OAuthUnavailableReason.REFRESH_FAILED, "访问令牌已过期，请重试刷新", "access_token_expired")
        }
        return decryptAccess(account)
    }

    /** 管理面手动刷新。即使账号已标记为需要重新授权，也允许再试一次。 */
    suspend fun refreshNow(accountId: String): OAuthRefreshResult {
        val account = repository.find(accountId) ?: return OAuthRefreshResult.NotOAuthAccount
        return try {
            val outcome = awaitRefresh(account, force = true)
            requireAccessToken(outcome.account)
            OAuthRefreshResult.Refreshed(repository.findDetailed(accountId) ?: outcome.account)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: OAuthTokenUnavailableException) {
            OAuthRefreshResult.Failed(cause.reason, cause.errorCode)
        } catch (cause: Exception) {
            if (!DiagnosticPreparationDeadline.isTimeout(cause)) throw cause
            OAuthRefreshResult.Failed(OAuthUnavailableReason.REFRESH_FAILED, "refresh_failed")
        }
    }

    /** 启动后台轮换。首次扫描延迟一个周期，启动期请求走懒刷新，避免惊群。 */
    fun start(scope: CoroutineScope) {
        if (worker.get() != null) return
        val job = scope.launch {
            while (isActive) {
                delay(config.refreshIntervalMs)
                try {
                    refreshDueAccounts()
                } catch (cause: CancellationException) {
                    throw cause
                } catch (cause: Throwable) {
                    logger.warn("oauth token refresh sweep failed: {}", cause::class.simpleName)
                }
            }
        }
        if (!worker.compareAndSet(null, job)) job.cancel()
    }

    /** Stop new exchanges before database shutdown; an in-flight JDBC worker still owns its lease. */
    suspend fun drainRefreshes(timeoutMs: Long = DEFAULT_REFRESH_DRAIN_TIMEOUT_MS): Boolean {
        require(timeoutMs >= 0)
        worker.getAndSet(null)?.cancel()
        val snapshot = synchronized(refreshLifecycleLock) {
            refreshAdmissionClosed = true
            refreshTasks.values.map { it.work }
        }
        val drained = snapshot.all { it.isCompleted } || withTimeoutOrNull(timeoutMs) {
            snapshot.forEach { it.join() }
            true
        } == true
        if (!drained) {
            // Shutdown is the sole boundary at which in-memory retries stop. This is not a durable
            // queue: process termination or a persistently unavailable database can lose a result.
            stopPersistenceRetries = true
            refreshSupervisor.cancel()
            val completed = withTimeoutOrNull(saturatedAdd(config.requestTimeoutMs, DIAGNOSTIC_REFRESH_DB_TIMEOUT_MS + 1_000)) {
                snapshot.forEach { it.join() }
                true
            } == true
            if (!completed) logger.warn("OAuth refresh shutdown still has an in-flight provider or JDBC operation")
        }
        val idlePending = synchronized(refreshLifecycleLock) {
            pendingRefreshes.filterKeys { id -> refreshTasks[id]?.work?.isCompleted != false }.toMap()
        }
        val shutdownBudget = DiagnosticPreparationDeadline.start(timeoutMs.coerceAtLeast(1))
        for ((id, pending) in idlePending) {
            val write = try {
                DiagnosticPreparationDeadline.withinSynchronousAndDrain(shutdownBudget) {
                    RefreshWrite(repository.saveRefreshed(
                        accountId = id, accessToken = pending.accessToken, expiresAt = pending.expiresAt,
                        tokenType = pending.tokenType, scope = pending.scope, rotatedRefreshToken = pending.rotatedRefreshToken,
                        expectedRefreshCiphertext = pending.context.refreshTokenCiphertext,
                        expectedAccount = pending.context.account, now = now(),
                    ))
                }
            } catch (_: Exception) { null }
            if (write != null) synchronized(refreshLifecycleLock) { pendingRefreshes.remove(id, pending) }
            else break // Keep the remaining encrypted results and report an incomplete drain.
        }
        val unsaved = synchronized(refreshLifecycleLock) { pendingRefreshes.size }
        if (unsaved != 0) logger.warn("OAuth shutdown retains {} unsaved encrypted rotation results", unsaved)
        refreshSupervisor.cancel()
        return drained && unsaved == 0
    }

    override fun close() {
        worker.getAndSet(null)?.cancel()
        synchronized(refreshLifecycleLock) { refreshAdmissionClosed = true }
        stopPersistenceRetries = true
        refreshSupervisor.cancel()
    }

    private suspend fun refreshDueAccounts() {
        val available = providers.available()
        if (available.isEmpty()) return
        val due = repository.listDueForRefresh(saturatedAdd(now(), config.refreshAheadMs)).filter { it.provider in available }
        if (due.isEmpty()) return
        val refreshed = AtomicInteger()
        val failed = AtomicInteger()
        val semaphore = Semaphore(MAX_CONCURRENT_REFRESHES)
        coroutineScope {
            due.map { account ->
                async {
                    semaphore.withPermit {
                        when (refreshNow(account.id)) {
                            is OAuthRefreshResult.Refreshed -> refreshed.incrementAndGet()
                            is OAuthRefreshResult.Failed -> failed.incrementAndGet()
                            OAuthRefreshResult.NotOAuthAccount -> Unit
                        }
                    }
                }
            }.awaitAll()
        }
        if (refreshed.get() > 0 || failed.get() > 0) {
            logger.info("oauth token refresh sweep: refreshed={}, failed={}", refreshed.get(), failed.get())
        }
    }

    private fun cachedAccessToken(account: OAuthAccountRecord): OAuthAccessToken? {
        if (account.authState != OAuthAuthState.ACTIVE || !account.hasAccessToken) return null
        if (account.refreshDueAt(config.refreshAheadMs) > now()) {
            return runCatching {
                OAuthAccessToken(
                    accessToken = cipher.decrypt(account.accessTokenCiphertext!!, account.accessTokenNonce!!),
                    expiresAt = account.accessTokenExpiresAt!!,
                    tokenType = account.tokenType,
                )
            }.getOrNull()
        }
        return null
    }

    private fun prepareRefresh(context: OAuthRefreshContext): PreparedRefresh {
        val provider = providers.find(context.account.provider)
            ?: throw OAuthTokenUnavailableException(
                OAuthUnavailableReason.PROVIDER_NOT_CONFIGURED,
                "OAuth 提供方 ${context.account.provider.value} 未配置",
            )
        return PreparedRefresh(context, provider)
    }

    private suspend fun exchange(prepared: PreparedRefresh): PendingRefresh {
        val context = prepared.context
        val refreshToken = try {
            cipher.decrypt(context.refreshTokenCiphertext, context.refreshTokenNonce)
        } catch (_: Exception) {
            throw OAuthRefreshException(true, "credential_decrypt_failed", "订阅账号凭据无法解密，请重新导入")
        }
        val response = prepared.provider.refresh(refreshToken, context.account.proxyUrl)
        return PendingRefresh(
            context = context,
            accessToken = cipher.encrypt(response.accessToken),
            rotatedRefreshToken = response.refreshToken?.takeIf { it.isNotBlank() }?.let(cipher::encrypt),
            expiresAt = saturatedAdd(now(), response.expiresInSeconds * 1_000),
            tokenType = response.tokenType,
            scope = response.scope,
        )
    }

    private suspend fun persistRefresh(pending: PendingRefresh, task: RefreshTask): OAuthAccountRecord {
        val context = pending.context
        var retryReported = false
        var attempts = 0
        while (true) {
            attempts++
            var permanentFailure = false
            // An exchange already admitted before shutdown must get its first save attempt.
            // Only subsequent retries may stop at the service shutdown boundary.
            val write = try {
                // Wait for the real transaction cleanup before a retry. An unknown commit is
                // identified under saveRefreshed's row lock using these same encrypted values.
                DiagnosticPreparationDeadline.withinSynchronousAndDrain(DIAGNOSTIC_REFRESH_DB_TIMEOUT_MS) {
                    RefreshWrite(repository.saveRefreshed(
                        accountId = context.account.id,
                        accessToken = pending.accessToken,
                        expiresAt = pending.expiresAt,
                        tokenType = pending.tokenType,
                        scope = pending.scope,
                        rotatedRefreshToken = pending.rotatedRefreshToken,
                        expectedRefreshCiphertext = context.refreshTokenCiphertext,
                        expectedAccount = context.account,
                        now = now(),
                    ))
                }
            } catch (cause: Exception) {
                permanentFailure = permanentRefreshWriteFailure(cause)
                if (!retryReported) {
                    logger.warn("oauth token save pending, accountId={}, error={}", context.account.id, cause::class.simpleName)
                    retryReported = true
                }
                null
            }
            if (write != null) {
                synchronized(refreshLifecycleLock) { pendingRefreshes.remove(context.account.id, pending) }
                val saved = write.account ?: throw credentialReplaced()
                if (saved.hasAccessToken) rememberRotation(context.account, saved)
                logger.debug("oauth token refreshed, accountId={}, provider={}, expiresAt={}",
                    saved.id, saved.provider.value, pending.expiresAt)
                return saved
            }
            val unavailable = OAuthTokenUnavailableException(OAuthUnavailableReason.REFRESH_FAILED,
                "账号刷新结果尚未保存，请稍后重试", "refresh_save_pending")
            task.persistenceFailure.complete(unavailable)
            if (stopPersistenceRetries || permanentFailure || attempts >= MAX_REFRESH_SAVE_ATTEMPTS) {
                // Keep only the encrypted response for a later same-account recovery call. The
                // completed task releases active refresh capacity without repeating the exchange.
                throw unavailable
            }
            if (!retryReported) {
                logger.warn("oauth token save timed out; retaining encrypted result, accountId={}", context.account.id)
                retryReported = true
            }
            delay(REFRESH_SAVE_RETRY_DELAY_MS)
        }
    }

    private fun discardReplacedPending(account: OAuthAccountRecord) = synchronized(refreshLifecycleLock) {
        val pending = pendingRefreshes[account.id] ?: return@synchronized
        if (refreshTasks[account.id]?.work?.isCompleted == false) return@synchronized
        val original = pending.context.account
        val belongsToAuthorization = account.provider == original.provider && account.authorizedAt == original.authorizedAt &&
            account.authState == original.authState
        val originalRefresh = account.refreshTokenCiphertext == original.refreshTokenCiphertext &&
            account.refreshTokenNonce == original.refreshTokenNonce && account.refreshTokenKeyVersion == original.refreshTokenKeyVersion
        val rotated = pending.rotatedRefreshToken
        val savedRefresh = rotated != null && account.refreshTokenCiphertext == rotated.ciphertext &&
            account.refreshTokenNonce == rotated.nonce && account.refreshTokenKeyVersion == rotated.keyVersion
        val originalAccess = account.accessTokenCiphertext == original.accessTokenCiphertext &&
            account.accessTokenNonce == original.accessTokenNonce && account.accessTokenKeyVersion == original.accessTokenKeyVersion
        val savedAccess = account.accessTokenCiphertext == pending.accessToken.ciphertext &&
            account.accessTokenNonce == pending.accessToken.nonce && account.accessTokenKeyVersion == pending.accessToken.keyVersion
        if (!belongsToAuthorization || (!originalRefresh && !savedRefresh) || (!originalAccess && !savedAccess && account.hasAccessToken)) {
            pendingRefreshes.remove(account.id, pending)
        }
    }

    private suspend fun prunePendingResults() {
        val retained = synchronized(refreshLifecycleLock) { pendingRefreshes.toMap() }
        val budget = DiagnosticPreparationDeadline.start(DIAGNOSTIC_REFRESH_DB_TIMEOUT_MS)
        for ((id, pending) in retained) {
            val current = DiagnosticPreparationDeadline.withinSynchronous(budget) {
                PendingAccount(repository.find(id))
            } ?: return
            if (current.account == null) synchronized(refreshLifecycleLock) {
                if (refreshTasks[id]?.work?.isCompleted != false) pendingRefreshes.remove(id, pending)
            } else discardReplacedPending(current.account)
        }
    }

    private class PendingAccount(val account: OAuthAccountRecord?)

    private fun permanentRefreshWriteFailure(cause: Throwable): Boolean = generateSequence(cause) { it.cause }.any {
        val state = (it as? java.sql.SQLException)?.sqlState
        state?.startsWith("28") == true || state?.startsWith("42") == true || state?.startsWith("23") == true
    }

    private suspend fun <T : Any> withinRefreshDatabaseBudget(write: suspend () -> T): T =
        DiagnosticPreparationDeadline.withinSynchronousAndDrain(DIAGNOSTIC_REFRESH_DB_TIMEOUT_MS, write)
            ?: throw OAuthTokenUnavailableException(OAuthUnavailableReason.REFRESH_FAILED, "账号刷新保存超时", "refresh_save_timeout")

    /**
     * 记录刷新失败。若凭据在刷新期间被管理员替换，放弃本次失败结果并提示重试，
     * 避免旧 refresh token 的终态错误把刚导入的新凭据标记为需要重新授权。
     */
    private suspend fun recordRefreshFailure(expected: OAuthAccountRecord, errorCode: String, terminal: Boolean) {
        val recorded = repository.recordFailure(
            accountId = expected.id,
            errorCode = errorCode,
            terminal = terminal,
            expectedRefreshCiphertext = expected.refreshTokenCiphertext,
            expectedAccount = expected,
            now = now(),
        )
        if (recorded == null) {
            throw OAuthTokenUnavailableException(OAuthUnavailableReason.REFRESH_FAILED, "订阅账号凭据已被替换，请重试", "credential_replaced")
        }
    }

    private fun eligibleWaiters(task: RefreshTask, latest: OAuthAccountRecord): List<RefreshWaiter> =
        task.waiters.filter { it.active() && (it.preparation == null || acceptsSnapshot(it.account, latest)) }

    private fun cachedRefreshOutcome(account: OAuthAccountRecord): RefreshOutcome? {
        if (account.authState != OAuthAuthState.ACTIVE) {
            throw OAuthTokenUnavailableException(OAuthUnavailableReason.REAUTH_REQUIRED, "订阅账号需要重新授权", account.lastRefreshError)
        }
        return cachedAccessToken(account)?.let { RefreshOutcome(account, exchanged = false) }
    }

    private fun lockFor(accountId: String): Mutex = synchronized(refreshLifecycleLock) {
        accountLocks.getOrPut(accountId) { Mutex() }
    }

    private companion object {
        const val LOCK_STRIPES = 64
        const val MAX_CONCURRENT_REFRESHES = 4
        const val MAX_CONCURRENT_DIAGNOSTIC_REFRESHES = 4
        const val MAX_DIAGNOSTIC_REFRESH_TASKS = 20
        const val MAX_PENDING_REFRESH_RESULTS = 512
        const val MAX_REFRESH_SAVE_ATTEMPTS = 3
        const val MAX_EXPIRED_TOKEN_RETRIES = 2
        const val MAX_ROTATION_ACCOUNTS = 512
        const val MAX_ROTATIONS_PER_ACCOUNT = 4
        const val REFRESH_SAVE_RETRY_DELAY_MS = 250L
        const val DEFAULT_REFRESH_DRAIN_TIMEOUT_MS = 5_000L
        const val DIAGNOSTIC_REFRESH_DB_TIMEOUT_MS = 10_000L
    }
}
