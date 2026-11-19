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
import dev.soven.prismllm.shared.domain.KeyCredentialResolver
import dev.soven.prismllm.shared.domain.UpstreamCredential
import dev.soven.prismllm.shared.domain.saturatedAdd
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    private val locks = Array(LOCK_STRIPES) { Mutex() }
    private val worker = AtomicReference<Job?>(null)
    private val refreshLifecycleLock = Any()
    private val refreshSupervisor = SupervisorJob()
    private val refreshScope = CoroutineScope(Dispatchers.IO + refreshSupervisor)
    private val diagnosticRefreshTasks = mutableMapOf<String, Deferred<DiagnosticOAuthAccess>>()
    private val diagnosticRefreshPermits = Semaphore(MAX_CONCURRENT_DIAGNOSTIC_REFRESHES)
    private var refreshAdmissionClosed = false

    /**
     * 返回可用的 access token。普通静态密钥返回 null；
     * OAuth 账号不可用时抛出 [OAuthTokenUnavailableException]，调用方应切换到其他候选。
     */
    suspend fun accessToken(accountId: String, forceRefresh: Boolean = false): OAuthAccessToken? {
        val account = repository.find(accountId) ?: return null
        return accessToken(account, forceRefresh)
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
        // Manual diagnosis has the same enabled-account semantics as the existing manual test.
        cachedAccessToken(account)?.let { return DiagnosticOAuthAccess(account, it.accessToken, account.proxyUrl) }
        val refresh = ownDiagnosticRefresh(account, preparation)
        return try {
            DiagnosticPreparationDeadline.within(preparation) { refresh.await() }
                ?: throw DiagnosticPreparationDeadline.deadlineExceeded()
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            if (DiagnosticPreparationDeadline.isTimeout(cause) || cause is OAuthTokenUnavailableException) throw cause
            throw OAuthTokenUnavailableException(OAuthUnavailableReason.REFRESH_FAILED, "订阅账号刷新失败", "refresh_failed")
        }
    }

    private fun ownDiagnosticRefresh(
        account: OAuthAccountRecord,
        preparation: DiagnosticPreparationDeadline.Budget,
    ): Deferred<DiagnosticOAuthAccess> = synchronized(refreshLifecycleLock) {
        check(!refreshAdmissionClosed) { "OAuth refresh service is closing" }
        diagnosticRefreshTasks[account.id]?.let { return@synchronized it }
        if (diagnosticRefreshTasks.size >= MAX_DIAGNOSTIC_REFRESH_TASKS) {
            throw OAuthTokenUnavailableException(OAuthUnavailableReason.REFRESH_FAILED, "账号刷新繁忙，请稍后重试", "refresh_busy")
        }
        val task = refreshScope.async(start = CoroutineStart.LAZY) {
            var permitHeld = false
            var accountMutex: Mutex? = null
            try {
                // Keep both resources until exchange+CAS finishes, including the handoff out of
                // this scope. Expiry before exchange releases them without contacting the provider.
                val action = DiagnosticPreparationDeadline.within(preparation) {
                    diagnosticRefreshPermits.acquire()
                    permitHeld = true
                    val mutex = lockFor(account.id)
                    mutex.lock()
                    accountMutex = mutex
                    val context = repository.loadRefreshContext(account.id)
                        ?: throw OAuthTokenUnavailableException(OAuthUnavailableReason.REAUTH_REQUIRED, "订阅账号已删除")
                    val latest = context.account
                    if (latest.provider != account.provider || latest.authorizedAt != account.authorizedAt ||
                        latest.authState != OAuthAuthState.ACTIVE ||
                        context.refreshTokenCiphertext != account.refreshTokenCiphertext ||
                        context.refreshTokenNonce != account.refreshTokenNonce ||
                        context.refreshTokenKeyVersion != account.refreshTokenKeyVersion ||
                        latest.accessTokenCiphertext != account.accessTokenCiphertext ||
                        latest.accessTokenNonce != account.accessTokenNonce ||
                        latest.accessTokenKeyVersion != account.accessTokenKeyVersion ||
                        latest.proxyUrl != account.proxyUrl
                    ) {
                        throw OAuthTokenUnavailableException(OAuthUnavailableReason.REAUTH_REQUIRED, "订阅账号当前不可用")
                    }
                    cachedAccessToken(latest)?.let {
                        return@within DiagnosticRefreshAction.Cached(DiagnosticOAuthAccess(latest, it.accessToken, latest.proxyUrl))
                    }
                    DiagnosticRefreshAction.Exchange(prepareRefresh(context))
                } ?: throw DiagnosticPreparationDeadline.deadlineExceeded()
                currentCoroutineContext().ensureActive()
                preparation.remainingMillis(currentCoroutineContext().job)
                when (action) {
                    is DiagnosticRefreshAction.Cached -> action.value
                    is DiagnosticRefreshAction.Exchange -> withContext(NonCancellable) {
                        // No diagnostic deadline is installed here. Provider request timeouts still
                        // apply; persistence has its own service budget, independent of all waiters.
                        val saved = refreshPrepared(action.refresh, serviceOwned = true)
                        DiagnosticOAuthAccess(saved.account, saved.token.accessToken, action.refresh.context.account.proxyUrl)
                    }
                }
            } finally {
                accountMutex?.unlock()
                if (permitHeld) diagnosticRefreshPermits.release()
            }
        }
        diagnosticRefreshTasks[account.id] = task
        task.invokeOnCompletion { cause ->
            synchronized(refreshLifecycleLock) {
                if (diagnosticRefreshTasks[account.id] === task) diagnosticRefreshTasks.remove(account.id)
            }
            if (cause != null && cause !is CancellationException) {
                logger.warn("oauth diagnostic refresh failed, accountId={}, error={}", account.id, cause::class.simpleName)
            }
        }
        task.start()
        task
    }

    private class PreparedRefresh(
        val context: OAuthRefreshContext,
        val provider: OAuthTokenProvider,
        val refreshToken: String,
    )

    private class PersistedAccessToken(val token: OAuthAccessToken, val account: OAuthAccountRecord)
    private class RefreshWrite(val account: OAuthAccountRecord?)

    private sealed interface DiagnosticRefreshAction {
        class Cached(val value: DiagnosticOAuthAccess) : DiagnosticRefreshAction
        class Exchange(val refresh: PreparedRefresh) : DiagnosticRefreshAction
    }

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

    private suspend fun accessToken(account: OAuthAccountRecord, forceRefresh: Boolean): OAuthAccessToken {
        if (!forceRefresh) cachedAccessToken(account)?.let { return it }
        return lockFor(account.id).withLock {
            val latest = repository.find(account.id)
                ?: throw OAuthTokenUnavailableException(OAuthUnavailableReason.REAUTH_REQUIRED, "订阅账号已删除")
            if (!forceRefresh) cachedAccessToken(latest)?.let { return@withLock it }
            refreshLocked(latest)
        }
    }

    /** 管理面手动刷新。即使账号已标记为需要重新授权，也允许再试一次。 */
    suspend fun refreshNow(accountId: String): OAuthRefreshResult {
        if (repository.find(accountId) == null) return OAuthRefreshResult.NotOAuthAccount
        return lockFor(accountId).withLock {
            val latest = repository.find(accountId) ?: return@withLock OAuthRefreshResult.NotOAuthAccount
            try {
                refreshLocked(latest, force = true)
                OAuthRefreshResult.Refreshed(repository.findDetailed(accountId) ?: latest)
            } catch (cause: OAuthTokenUnavailableException) {
                OAuthRefreshResult.Failed(cause.reason, cause.errorCode)
            }
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

    /** Stop admission and drain before closing the database. Only pre-exchange tasks are cancelled. */
    suspend fun drainRefreshes(timeoutMs: Long = DEFAULT_REFRESH_DRAIN_TIMEOUT_MS): Boolean {
        require(timeoutMs >= 0)
        val snapshot = synchronized(refreshLifecycleLock) {
            refreshAdmissionClosed = true
            diagnosticRefreshTasks.values.toList()
        }
        val drained = snapshot.all { it.isCompleted } || withTimeoutOrNull(timeoutMs) {
            snapshot.forEach { it.join() }
            true
        } == true
        if (!drained) {
            refreshSupervisor.cancel()
            // Built-in providers use config.requestTimeoutMs. A started exchange retains ownership
            // through its separately bounded DB write, so shutdown never abandons a rotated token.
            val completed = withTimeoutOrNull(saturatedAdd(config.requestTimeoutMs, DIAGNOSTIC_REFRESH_DB_TIMEOUT_MS + 1_000)) {
                snapshot.forEach { it.join() }
                true
            } == true
            if (!completed) logger.warn("OAuth refresh drain exceeded its provider and database shutdown budgets")
        }
        refreshSupervisor.cancel()
        return drained
    }

    override fun close() {
        worker.getAndSet(null)?.cancel()
        synchronized(refreshLifecycleLock) { refreshAdmissionClosed = true }
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

    private suspend fun refreshLocked(account: OAuthAccountRecord, force: Boolean = false): OAuthAccessToken {
        if (!force && account.authState == OAuthAuthState.REAUTH_REQUIRED) {
            throw OAuthTokenUnavailableException(OAuthUnavailableReason.REAUTH_REQUIRED, "订阅账号需要重新授权", account.lastRefreshError)
        }
        val context = repository.loadRefreshContext(account.id)
            ?: throw OAuthTokenUnavailableException(OAuthUnavailableReason.REAUTH_REQUIRED, "订阅账号已删除")
        return refreshPrepared(prepareRefresh(context), serviceOwned = false).token
    }

    private suspend fun prepareRefresh(context: OAuthRefreshContext): PreparedRefresh {
        val provider = providers.find(context.account.provider)
            ?: throw OAuthTokenUnavailableException(
                OAuthUnavailableReason.PROVIDER_NOT_CONFIGURED,
                "OAuth 提供方 ${context.account.provider.value} 未配置",
            )
        val refreshToken = try {
            cipher.decrypt(context.refreshTokenCiphertext, context.refreshTokenNonce)
        } catch (_: Exception) {
            recordRefreshFailure(context.account.id, "credential_decrypt_failed", terminal = true, context.refreshTokenCiphertext)
            throw OAuthTokenUnavailableException(
                OAuthUnavailableReason.REAUTH_REQUIRED,
                "订阅账号凭据无法解密，请重新导入",
                "credential_decrypt_failed",
            )
        }
        return PreparedRefresh(context, provider, refreshToken)
    }

    private suspend fun refreshPrepared(prepared: PreparedRefresh, serviceOwned: Boolean): PersistedAccessToken {
        val context = prepared.context
        val response = try {
            prepared.provider.refresh(prepared.refreshToken, context.account.proxyUrl)
        } catch (cause: OAuthRefreshException) {
            withinRefreshDatabaseBudget(serviceOwned) {
                recordRefreshFailure(context.account.id, cause.errorCode, cause.terminal, context.refreshTokenCiphertext)
            }
            throw OAuthTokenUnavailableException(
                if (cause.terminal) OAuthUnavailableReason.REAUTH_REQUIRED else OAuthUnavailableReason.REFRESH_FAILED,
                cause.message ?: "订阅账号刷新失败",
                cause.errorCode,
            )
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            if (cause is VirtualMachineError) throw cause
            withinRefreshDatabaseBudget(serviceOwned) {
                recordRefreshFailure(context.account.id, "refresh_failed", terminal = false, context.refreshTokenCiphertext)
            }
            throw OAuthTokenUnavailableException(OAuthUnavailableReason.REFRESH_FAILED, "订阅账号刷新失败", "refresh_failed")
        }
        val accessToken = cipher.encrypt(response.accessToken)
        val rotatedRefreshToken = response.refreshToken?.takeIf { it.isNotBlank() }?.let(cipher::encrypt)
        val expiresAt = saturatedAdd(now(), response.expiresInSeconds * 1_000)
        val saved = withinRefreshDatabaseBudget(serviceOwned) {
            RefreshWrite(repository.saveRefreshed(
                accountId = context.account.id,
                accessToken = accessToken,
                expiresAt = expiresAt,
                tokenType = response.tokenType,
                scope = response.scope,
                rotatedRefreshToken = rotatedRefreshToken,
                expectedRefreshCiphertext = context.refreshTokenCiphertext,
                now = now(),
            ))
        }.account ?: throw OAuthTokenUnavailableException(
            OAuthUnavailableReason.REFRESH_FAILED,
            "订阅账号凭据已被替换，请重试",
            "credential_replaced",
        )
        logger.debug("oauth token refreshed, accountId={}, provider={}, expiresAt={}", context.account.id, context.account.provider.value, expiresAt)
        return PersistedAccessToken(OAuthAccessToken(response.accessToken, expiresAt, response.tokenType), saved)
    }

    private suspend fun <T : Any> withinRefreshDatabaseBudget(serviceOwned: Boolean, write: suspend () -> T): T {
        if (!serviceOwned) return write()
        return DiagnosticPreparationDeadline.within(DIAGNOSTIC_REFRESH_DB_TIMEOUT_MS, write)
            ?: throw OAuthTokenUnavailableException(OAuthUnavailableReason.REFRESH_FAILED, "账号刷新保存超时", "refresh_save_timeout")
    }

    /**
     * 记录刷新失败。若凭据在刷新期间被管理员替换，放弃本次失败结果并提示重试，
     * 避免旧 refresh token 的终态错误把刚导入的新凭据标记为需要重新授权。
     */
    private suspend fun recordRefreshFailure(accountId: String, errorCode: String, terminal: Boolean, expectedRefreshCiphertext: String) {
        val recorded = repository.recordFailure(
            accountId = accountId,
            errorCode = errorCode,
            terminal = terminal,
            expectedRefreshCiphertext = expectedRefreshCiphertext,
            now = now(),
        )
        if (recorded == null) {
            throw OAuthTokenUnavailableException(OAuthUnavailableReason.REFRESH_FAILED, "订阅账号凭据已被替换，请重试", "credential_replaced")
        }
    }

    private fun lockFor(accountId: String): Mutex = locks[(accountId.hashCode() and Int.MAX_VALUE) % locks.size]

    private companion object {
        const val LOCK_STRIPES = 64
        const val MAX_CONCURRENT_REFRESHES = 4
        const val MAX_CONCURRENT_DIAGNOSTIC_REFRESHES = 4
        const val MAX_DIAGNOSTIC_REFRESH_TASKS = 20
        const val DEFAULT_REFRESH_DRAIN_TIMEOUT_MS = 5_000L
        const val DIAGNOSTIC_REFRESH_DB_TIMEOUT_MS = 10_000L
    }
}
