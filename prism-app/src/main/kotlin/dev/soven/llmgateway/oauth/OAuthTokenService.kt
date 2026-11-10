package dev.soven.llmgateway.oauth

import dev.soven.llmgateway.config.OAuthConfig
import dev.soven.llmgateway.channel.domain.ChannelKeyRecord
import dev.soven.llmgateway.oauth.domain.OAuthAccessToken
import dev.soven.llmgateway.oauth.domain.OAuthAccountRecord
import dev.soven.llmgateway.oauth.domain.OAuthAuthState
import dev.soven.llmgateway.oauth.infra.OAuthRefreshException
import dev.soven.llmgateway.oauth.infra.OAuthTokenProviderRegistry
import dev.soven.llmgateway.shared.domain.KeyCredentialResolver
import dev.soven.llmgateway.shared.domain.UpstreamCredential
import dev.soven.llmgateway.shared.domain.saturatedAdd
import dev.soven.llmgateway.oauth.persistence.OAuthAccountRepository
import dev.soven.llmgateway.shared.crypto.CredentialCipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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

/**
 * 订阅账号 access token 的解析、刷新与后台轮换。
 *
 * 约束：
 * - 同一账号的刷新按 accountId 串行，避免并发刷新把 refresh token 轮换链打断。
 * - 刷新失败不修改账号启停状态；账号不可用由 [OAuthAccountRecord.authState] 表达。
 * - 后台任务只处理仍可自动刷新的账号，终态账号等待管理员重新导入。
 * - 日志只记录 accountId、provider 和错误码，不记录任何 token。
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

    /**
     * 返回可用的 access token。普通静态密钥返回 null；
     * OAuth 账号不可用时抛出 [OAuthTokenUnavailableException]，调用方应切换到其他候选。
     */
    suspend fun accessToken(accountId: String, forceRefresh: Boolean = false): OAuthAccessToken? {
        val account = repository.find(accountId) ?: return null
        return accessToken(account, forceRefresh)
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
            ?: throw OAuthTokenUnavailableException(
                OAuthUnavailableReason.REAUTH_REQUIRED,
                "订阅账号已删除",
            )
        else -> throw IllegalStateException("未知的上游凭据类型: ${credential::class.qualifiedName}")
    }

    private suspend fun accessToken(account: OAuthAccountRecord, forceRefresh: Boolean): OAuthAccessToken {
        if (!forceRefresh) cachedAccessToken(account)?.let { return it }
        return lockFor(account.id).withLock {
            val latest = repository.find(account.id)
                ?: throw OAuthTokenUnavailableException(
                    OAuthUnavailableReason.REAUTH_REQUIRED,
                    "订阅账号已删除",
                )
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

    override fun close() {
        worker.getAndSet(null)?.cancel()
    }

    private suspend fun refreshDueAccounts() {
        val available = providers.available()
        if (available.isEmpty()) return
        val due = repository.listDueForRefresh(saturatedAdd(now(), config.refreshAheadMs))
            .filter { it.provider in available }
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
            throw OAuthTokenUnavailableException(
                OAuthUnavailableReason.REAUTH_REQUIRED,
                "订阅账号需要重新授权",
                account.lastRefreshError,
            )
        }
        val context = repository.loadRefreshContext(account.id)
            ?: throw OAuthTokenUnavailableException(OAuthUnavailableReason.REAUTH_REQUIRED, "订阅账号已删除")
        val provider = providers.find(context.account.provider)
            ?: throw OAuthTokenUnavailableException(
                OAuthUnavailableReason.PROVIDER_NOT_CONFIGURED,
                "OAuth 提供方 ${context.account.provider.value} 未配置",
            )
        val refreshToken = try {
            cipher.decrypt(context.refreshTokenCiphertext, context.refreshTokenNonce)
        } catch (_: Exception) {
            recordRefreshFailure(account.id, "credential_decrypt_failed", terminal = true, context.refreshTokenCiphertext)
            throw OAuthTokenUnavailableException(
                OAuthUnavailableReason.REAUTH_REQUIRED,
                "订阅账号凭据无法解密，请重新导入",
                "credential_decrypt_failed",
            )
        }
        val response = try {
            provider.refresh(refreshToken, context.account.proxyUrl)
        } catch (cause: OAuthRefreshException) {
            recordRefreshFailure(account.id, cause.errorCode, cause.terminal, context.refreshTokenCiphertext)
            throw OAuthTokenUnavailableException(
                if (cause.terminal) OAuthUnavailableReason.REAUTH_REQUIRED else OAuthUnavailableReason.REFRESH_FAILED,
                cause.message ?: "订阅账号刷新失败",
                cause.errorCode,
            )
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            if (cause is VirtualMachineError) throw cause
            recordRefreshFailure(account.id, "refresh_failed", terminal = false, context.refreshTokenCiphertext)
            throw OAuthTokenUnavailableException(
                OAuthUnavailableReason.REFRESH_FAILED,
                "订阅账号刷新失败",
                "refresh_failed",
            )
        }
        val accessToken = cipher.encrypt(response.accessToken)
        val rotatedRefreshToken = response.refreshToken?.takeIf { it.isNotBlank() }?.let(cipher::encrypt)
        val expiresAt = saturatedAdd(now(), response.expiresInSeconds * 1_000)
        repository.saveRefreshed(
            accountId = account.id,
            accessToken = accessToken,
            expiresAt = expiresAt,
            tokenType = response.tokenType,
            scope = response.scope,
            rotatedRefreshToken = rotatedRefreshToken,
            expectedRefreshCiphertext = context.refreshTokenCiphertext,
            now = now(),
        ) ?: throw OAuthTokenUnavailableException(
            // 凭据被管理员并发替换：本次结果作废，但账号本身仍可重试。
            OAuthUnavailableReason.REFRESH_FAILED,
            "订阅账号凭据已被替换，请重试",
            "credential_replaced",
        )
        logger.debug(
            "oauth token refreshed, accountId={}, provider={}, expiresAt={}",
            account.id,
            context.account.provider.value,
            expiresAt,
        )
        return OAuthAccessToken(response.accessToken, expiresAt, response.tokenType)
    }

    /**
     * 记录刷新失败。若凭据在刷新期间被管理员替换，放弃本次失败结果并提示重试，
     * 避免旧 refresh token 的终态错误把刚导入的新凭据标记为需要重新授权。
     */
    private suspend fun recordRefreshFailure(
        accountId: String,
        errorCode: String,
        terminal: Boolean,
        expectedRefreshCiphertext: String,
    ) {
        val recorded = repository.recordFailure(
            accountId = accountId,
            errorCode = errorCode,
            terminal = terminal,
            expectedRefreshCiphertext = expectedRefreshCiphertext,
            now = now(),
        )
        if (recorded == null) {
            throw OAuthTokenUnavailableException(
                OAuthUnavailableReason.REFRESH_FAILED,
                "订阅账号凭据已被替换，请重试",
                "credential_replaced",
            )
        }
    }

    private fun lockFor(accountId: String): Mutex = locks[(accountId.hashCode() and Int.MAX_VALUE) % locks.size]

    private companion object {
        const val LOCK_STRIPES = 64
        const val MAX_CONCURRENT_REFRESHES = 4
    }
}
