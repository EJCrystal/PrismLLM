package dev.soven.llmgateway.repo

import dev.soven.llmgateway.db.ChannelKeyOAuthAccounts
import dev.soven.llmgateway.db.ChannelKeys
import dev.soven.llmgateway.domain.OAuthAccountPolicy
import dev.soven.llmgateway.domain.OAuthAccountRecord
import dev.soven.llmgateway.domain.OAuthAuthState
import dev.soven.llmgateway.domain.OAuthProvider
import dev.soven.llmgateway.domain.OAuthQuotaSnapshot
import dev.soven.llmgateway.domain.OAuthQuotaWindow
import dev.soven.llmgateway.security.EncryptedCredential
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

/** 刷新时同时需要账号状态和 channel_keys 中 refresh token 的密文。 */
data class OAuthRefreshContext(
    val account: OAuthAccountRecord,
    val refreshTokenCiphertext: String,
    val refreshTokenNonce: String,
    val refreshTokenKeyVersion: Int,
)

/**
 * 订阅账号 OAuth 状态的持久化。只读写密文，不解密、不记录任何 token。
 *
 * @author 文杰
 * @since 2026-11-06
 */
class OAuthAccountRepository(private val database: Database) {
    suspend fun find(keyId: String): OAuthAccountRecord? = suspendTransaction(database) {
        ChannelKeyOAuthAccounts.selectAll()
            .where { ChannelKeyOAuthAccounts.keyId eq keyId }
            .singleOrNull()
            ?.let(::toRecord)
    }

    /** 管理面账号看板：按最近变更倒序返回全部订阅账号。 */
    suspend fun listAll(limit: Int = 5_000): List<OAuthAccountRecord> = suspendTransaction(database) {
        ChannelKeyOAuthAccounts.selectAll()
            .orderBy(ChannelKeyOAuthAccounts.updatedAt to SortOrder.DESC)
            .limit(limit.coerceIn(1, 20_000))
            .map(::toRecord)
    }

    /** 后台扫描：只返回仍可自动刷新、且已进入提前刷新窗口的账号。 */
    suspend fun listDueForRefresh(dueBefore: Long, limit: Int = 100): List<OAuthAccountRecord> =
        suspendTransaction(database) {
            ChannelKeyOAuthAccounts.selectAll()
                .where {
                    (ChannelKeyOAuthAccounts.authState eq OAuthAuthState.ACTIVE.value) and
                        (
                            ChannelKeyOAuthAccounts.accessTokenExpiresAt.isNull() or
                                (ChannelKeyOAuthAccounts.accessTokenExpiresAt less dueBefore)
                            )
                }
                // 尚未拿到过 access token 的账号优先，避免长期被到期账号挤占批次。
                .orderBy(ChannelKeyOAuthAccounts.accessTokenExpiresAt to SortOrder.ASC_NULLS_FIRST)
                .limit(limit.coerceIn(1, 1_000))
                .map(::toRecord)
        }

    /**
     * 读取一次刷新所需的全部输入。没有 OAuth 绑定返回 null，表示这是普通静态密钥。
     */
    suspend fun loadRefreshContext(keyId: String): OAuthRefreshContext? = suspendTransaction(database) {
        val accountRow = ChannelKeyOAuthAccounts.selectAll()
            .where { ChannelKeyOAuthAccounts.keyId eq keyId }
            .singleOrNull()
            ?: return@suspendTransaction null
        val keyRow = ChannelKeys.selectAll()
            .where { ChannelKeys.id eq keyId }
            .singleOrNull()
            ?: return@suspendTransaction null
        OAuthRefreshContext(
            account = toRecord(accountRow),
            refreshTokenCiphertext = keyRow[ChannelKeys.credentialCiphertext],
            refreshTokenNonce = keyRow[ChannelKeys.credentialNonce],
            refreshTokenKeyVersion = keyRow[ChannelKeys.credentialKeyVersion],
        )
    }

    /**
     * 导入或重新授权一个订阅账号。refresh token 与 OAuth 行在同一事务写入；
     * 未提供 access token 时清空旧缓存，避免把旧账号的 token 用到新凭据上。
     */
    suspend fun upsert(
        keyId: String,
        provider: OAuthProvider,
        accountEmail: String? = null,
        accountPlan: String? = null,
        proxyUrl: String? = null,
        refreshToken: EncryptedCredential? = null,
        accessToken: EncryptedCredential? = null,
        accessTokenExpiresAt: Long? = null,
        tokenType: String = "Bearer",
        scope: String? = null,
        now: Long = System.currentTimeMillis(),
    ): OAuthAccountRecord = suspendTransaction(database) {
        require((accessToken == null) == (accessTokenExpiresAt == null)) {
            "access token and expiry must be provided together"
        }
        val keyRow = ChannelKeys.selectAll()
            .where { ChannelKeys.id eq keyId }
            .forUpdate()
            .singleOrNull()
            ?: throw IllegalArgumentException("channel key not found: $keyId")
        if (refreshToken != null) {
            // 管理员导入属于凭据变更：推进 state_version，让并发编辑感知冲突，并触发冷却清理。
            ChannelKeys.update({ ChannelKeys.id eq keyId }) {
                it[credentialCiphertext] = refreshToken.ciphertext
                it[credentialNonce] = refreshToken.nonce
                it[credentialKeyVersion] = refreshToken.keyVersion
                it[updatedAt] = now
                it[stateVersion] = keyRow[ChannelKeys.stateVersion] + 1
            }
        }
        val existing = ChannelKeyOAuthAccounts.selectAll()
            .where { ChannelKeyOAuthAccounts.keyId eq keyId }
            .forUpdate()
            .singleOrNull()
        if (existing == null) {
            ChannelKeyOAuthAccounts.insert {
                it[ChannelKeyOAuthAccounts.keyId] = keyId
                it[ChannelKeyOAuthAccounts.provider] = provider.value
                it[ChannelKeyOAuthAccounts.accountEmail] = accountEmail
                it[ChannelKeyOAuthAccounts.accountPlan] = accountPlan
                it[ChannelKeyOAuthAccounts.accessTokenCiphertext] = accessToken?.ciphertext
                it[ChannelKeyOAuthAccounts.accessTokenNonce] = accessToken?.nonce
                it[ChannelKeyOAuthAccounts.accessTokenKeyVersion] = accessToken?.keyVersion
                it[ChannelKeyOAuthAccounts.accessTokenExpiresAt] = accessTokenExpiresAt
                it[ChannelKeyOAuthAccounts.tokenType] = tokenType
                it[ChannelKeyOAuthAccounts.scope] = scope
                it[ChannelKeyOAuthAccounts.authState] = OAuthAuthState.ACTIVE.value
                it[ChannelKeyOAuthAccounts.refreshFailureCount] = 0
                it[ChannelKeyOAuthAccounts.lastRefreshError] = null
                it[ChannelKeyOAuthAccounts.lastRefreshedAt] = if (accessToken != null) now else null
                it[ChannelKeyOAuthAccounts.proxyUrl] = proxyUrl
                it[ChannelKeyOAuthAccounts.createdAt] = now
                it[ChannelKeyOAuthAccounts.updatedAt] = now
            }
        } else {
            ChannelKeyOAuthAccounts.update({ ChannelKeyOAuthAccounts.keyId eq keyId }) {
                it[ChannelKeyOAuthAccounts.provider] = provider.value
                it[ChannelKeyOAuthAccounts.accountEmail] = accountEmail
                it[ChannelKeyOAuthAccounts.accountPlan] = accountPlan
                it[ChannelKeyOAuthAccounts.accessTokenCiphertext] = accessToken?.ciphertext
                it[ChannelKeyOAuthAccounts.accessTokenNonce] = accessToken?.nonce
                it[ChannelKeyOAuthAccounts.accessTokenKeyVersion] = accessToken?.keyVersion
                it[ChannelKeyOAuthAccounts.accessTokenExpiresAt] = accessTokenExpiresAt
                it[ChannelKeyOAuthAccounts.tokenType] = tokenType
                it[ChannelKeyOAuthAccounts.scope] = scope
                it[ChannelKeyOAuthAccounts.authState] = OAuthAuthState.ACTIVE.value
                it[ChannelKeyOAuthAccounts.refreshFailureCount] = 0
                it[ChannelKeyOAuthAccounts.lastRefreshError] = null
                it[ChannelKeyOAuthAccounts.lastRefreshedAt] = if (accessToken != null) now else null
                it[ChannelKeyOAuthAccounts.proxyUrl] = proxyUrl
                it[ChannelKeyOAuthAccounts.updatedAt] = now
            }
        }
        ChannelKeyOAuthAccounts.selectAll()
            .where { ChannelKeyOAuthAccounts.keyId eq keyId }
            .single()
            .let(::toRecord)
    }

    /** 后台额度扫描：只返回仍可自动刷新、且读数已过期的 ChatGPT 账号。 */
    suspend fun listDueForQuota(checkedBefore: Long, limit: Int = 100): List<OAuthAccountRecord> =
        suspendTransaction(database) {
            ChannelKeyOAuthAccounts.selectAll()
                .where {
                    (ChannelKeyOAuthAccounts.authState eq OAuthAuthState.ACTIVE.value) and
                        (ChannelKeyOAuthAccounts.provider eq OAuthProvider.CHATGPT.value) and
                        (
                            ChannelKeyOAuthAccounts.quotaCheckedAt.isNull() or
                                (ChannelKeyOAuthAccounts.quotaCheckedAt less checkedBefore)
                            )
                }
                .orderBy(ChannelKeyOAuthAccounts.quotaCheckedAt to SortOrder.ASC_NULLS_FIRST)
                .limit(limit.coerceIn(1, 1_000))
                .map(::toRecord)
        }

    /** 从 access token 解析出的 ChatGPT 账号 id，刷新成功后写入。 */
    suspend fun updateChatgptAccountId(keyId: String, accountId: String): Boolean = suspendTransaction(database) {
        ChannelKeyOAuthAccounts.update({
            (ChannelKeyOAuthAccounts.keyId eq keyId) and
                (ChannelKeyOAuthAccounts.chatgptAccountId.isNull() or (ChannelKeyOAuthAccounts.chatgptAccountId neq accountId))
        }) {
            it[chatgptAccountId] = accountId.take(128)
        } > 0
    }

    /** 保存一次成功的额度读数；`accountPlan` 非空时同步上游给出的套餐名。 */
    suspend fun saveQuota(
        keyId: String,
        snapshot: OAuthQuotaSnapshot,
        accountPlan: String? = null,
    ): Boolean = suspendTransaction(database) {
        ChannelKeyOAuthAccounts.update({ ChannelKeyOAuthAccounts.keyId eq keyId }) {
            it[quotaPrimaryUsedPercent] = snapshot.primary?.usedPercent?.coerceIn(0.0, 100.0)
            it[quotaPrimaryWindowSeconds] = snapshot.primary?.windowSeconds
            it[quotaPrimaryResetsAt] = snapshot.primary?.resetsAt
            it[quotaSecondaryUsedPercent] = snapshot.secondary?.usedPercent?.coerceIn(0.0, 100.0)
            it[quotaSecondaryWindowSeconds] = snapshot.secondary?.windowSeconds
            it[quotaSecondaryResetsAt] = snapshot.secondary?.resetsAt
            it[quotaAllowed] = snapshot.allowed
            it[quotaCreditsBalance] = snapshot.creditsBalance
            it[quotaCreditsUnlimited] = snapshot.creditsUnlimited
            it[quotaResetCredits] = snapshot.resetCredits
            it[quotaCheckedAt] = snapshot.checkedAt
            it[quotaError] = null
            if (accountPlan != null) it[ChannelKeyOAuthAccounts.accountPlan] = accountPlan.take(32)
        } > 0
    }

    /** 记录一次额度读取失败；保留上一次成功读数，只更新错误信息。 */
    suspend fun recordQuotaFailure(keyId: String, errorCode: String): Boolean = suspendTransaction(database) {
        ChannelKeyOAuthAccounts.update({ ChannelKeyOAuthAccounts.keyId eq keyId }) {
            it[quotaError] = sanitizeQuotaError(errorCode)
        } > 0
    }

    /** 更新账号级调度策略；重新导入凭据不会重置这里的管理员配置。 */
    suspend fun updatePolicy(
        keyId: String,
        policy: OAuthAccountPolicy,
        now: Long = System.currentTimeMillis(),
    ): Boolean = suspendTransaction(database) {
        ChannelKeyOAuthAccounts.update({ ChannelKeyOAuthAccounts.keyId eq keyId }) {
            it[windowLimit] = policy.windowLimit
            it[windowSeconds] = policy.windowSeconds
            it[maxConcurrentRequests] = policy.maxConcurrentRequests
            it[updatedAt] = now
        } > 0
    }

    /**
     * 保存一次成功的刷新。整个写入以 channel_keys 中的凭据密文仍等于本次读取值为前提，
     * 避免把旧账号的 access token 覆盖到管理员并发导入的新凭据上。
     */
    suspend fun saveRefreshed(
        keyId: String,
        accessToken: EncryptedCredential,
        expiresAt: Long,
        tokenType: String,
        scope: String?,
        rotatedRefreshToken: EncryptedCredential?,
        expectedRefreshCiphertext: String,
        now: Long = System.currentTimeMillis(),
    ): OAuthAccountRecord? = suspendTransaction(database) {
        // 与 upsert 使用同一把行锁，二者串行；凭据已变化时放弃本次刷新结果。
        val current = ChannelKeys.selectAll()
            .where { ChannelKeys.id eq keyId }
            .forUpdate()
            .singleOrNull()
            ?: return@suspendTransaction null
        if (current[ChannelKeys.credentialCiphertext] != expectedRefreshCiphertext) {
            return@suspendTransaction null
        }
        val updated = ChannelKeyOAuthAccounts.update({ ChannelKeyOAuthAccounts.keyId eq keyId }) {
            it[accessTokenCiphertext] = accessToken.ciphertext
            it[accessTokenNonce] = accessToken.nonce
            it[accessTokenKeyVersion] = accessToken.keyVersion
            it[accessTokenExpiresAt] = expiresAt
            it[ChannelKeyOAuthAccounts.tokenType] = tokenType
            it[ChannelKeyOAuthAccounts.scope] = scope
            it[authState] = OAuthAuthState.ACTIVE.value
            it[refreshFailureCount] = 0
            it[lastRefreshError] = null
            it[lastRefreshedAt] = now
            it[ChannelKeyOAuthAccounts.updatedAt] = now
        }
        if (updated == 0) return@suspendTransaction null
        if (rotatedRefreshToken != null) {
            ChannelKeys.update({ ChannelKeys.id eq keyId }) {
                it[credentialCiphertext] = rotatedRefreshToken.ciphertext
                it[credentialNonce] = rotatedRefreshToken.nonce
                it[credentialKeyVersion] = rotatedRefreshToken.keyVersion
            }
        }
        ChannelKeyOAuthAccounts.selectAll()
            .where { ChannelKeyOAuthAccounts.keyId eq keyId }
            .single()
            .let(::toRecord)
    }

    /** 记录一次刷新失败；终态错误同时把账号标记为需要重新授权。 */
    suspend fun recordFailure(
        keyId: String,
        errorCode: String,
        terminal: Boolean,
        now: Long = System.currentTimeMillis(),
    ): OAuthAccountRecord? = suspendTransaction(database) {
        val row = ChannelKeyOAuthAccounts.selectAll()
            .where { ChannelKeyOAuthAccounts.keyId eq keyId }
            .forUpdate()
            .singleOrNull()
            ?: return@suspendTransaction null
        val failureCount = row[ChannelKeyOAuthAccounts.refreshFailureCount] + 1
        ChannelKeyOAuthAccounts.update({ ChannelKeyOAuthAccounts.keyId eq keyId }) {
            it[refreshFailureCount] = failureCount
            it[lastRefreshError] = sanitizeErrorCode(errorCode)
            if (terminal) it[authState] = OAuthAuthState.REAUTH_REQUIRED.value
            it[ChannelKeyOAuthAccounts.updatedAt] = now
        }
        ChannelKeyOAuthAccounts.selectAll()
            .where { ChannelKeyOAuthAccounts.keyId eq keyId }
            .single()
            .let(::toRecord)
    }

    private fun toRecord(row: ResultRow) = OAuthAccountRecord(
        keyId = row[ChannelKeyOAuthAccounts.keyId],
        provider = OAuthProvider.fromValue(row[ChannelKeyOAuthAccounts.provider])
            ?: throw IllegalStateException("未知的 OAuth provider: ${row[ChannelKeyOAuthAccounts.provider]}"),
        accountEmail = row[ChannelKeyOAuthAccounts.accountEmail],
        accountPlan = row[ChannelKeyOAuthAccounts.accountPlan],
        accessTokenCiphertext = row[ChannelKeyOAuthAccounts.accessTokenCiphertext],
        accessTokenNonce = row[ChannelKeyOAuthAccounts.accessTokenNonce],
        accessTokenKeyVersion = row[ChannelKeyOAuthAccounts.accessTokenKeyVersion],
        accessTokenExpiresAt = row[ChannelKeyOAuthAccounts.accessTokenExpiresAt],
        tokenType = row[ChannelKeyOAuthAccounts.tokenType],
        scope = row[ChannelKeyOAuthAccounts.scope],
        authState = OAuthAuthState.fromValue(row[ChannelKeyOAuthAccounts.authState])
            ?: throw IllegalStateException("未知的 OAuth 账号状态: ${row[ChannelKeyOAuthAccounts.authState]}"),
        refreshFailureCount = row[ChannelKeyOAuthAccounts.refreshFailureCount],
        lastRefreshError = row[ChannelKeyOAuthAccounts.lastRefreshError],
        lastRefreshedAt = row[ChannelKeyOAuthAccounts.lastRefreshedAt],
        proxyUrl = row[ChannelKeyOAuthAccounts.proxyUrl],
        chatgptAccountId = row[ChannelKeyOAuthAccounts.chatgptAccountId],
        quota = toQuota(row),
        createdAt = row[ChannelKeyOAuthAccounts.createdAt],
        updatedAt = row[ChannelKeyOAuthAccounts.updatedAt],
    )

    private fun toQuota(row: ResultRow): OAuthQuotaSnapshot? {
        val checkedAt = row[ChannelKeyOAuthAccounts.quotaCheckedAt]
        val error = row[ChannelKeyOAuthAccounts.quotaError]
        if (checkedAt == null && error == null) return null
        return OAuthQuotaSnapshot(
            primary = quotaWindow(
                row[ChannelKeyOAuthAccounts.quotaPrimaryUsedPercent],
                row[ChannelKeyOAuthAccounts.quotaPrimaryWindowSeconds],
                row[ChannelKeyOAuthAccounts.quotaPrimaryResetsAt],
            ),
            secondary = quotaWindow(
                row[ChannelKeyOAuthAccounts.quotaSecondaryUsedPercent],
                row[ChannelKeyOAuthAccounts.quotaSecondaryWindowSeconds],
                row[ChannelKeyOAuthAccounts.quotaSecondaryResetsAt],
            ),
            allowed = row[ChannelKeyOAuthAccounts.quotaAllowed],
            creditsBalance = row[ChannelKeyOAuthAccounts.quotaCreditsBalance],
            creditsUnlimited = row[ChannelKeyOAuthAccounts.quotaCreditsUnlimited],
            resetCredits = row[ChannelKeyOAuthAccounts.quotaResetCredits],
            checkedAt = checkedAt ?: 0,
            error = error,
        )
    }

    private fun quotaWindow(usedPercent: Double?, windowSeconds: Int?, resetsAt: Long?): OAuthQuotaWindow? =
        usedPercent?.let { OAuthQuotaWindow(it, windowSeconds, resetsAt) }

    private fun sanitizeErrorCode(value: String): String = value
        .filter { it.isLetterOrDigit() || it in "._-" }
        .take(80)
        .ifBlank { "refresh_failed" }

    private fun sanitizeQuotaError(value: String): String = value
        .filter { it.isLetterOrDigit() || it in "._-" }
        .take(120)
        .ifBlank { "quota_read_failed" }
}
