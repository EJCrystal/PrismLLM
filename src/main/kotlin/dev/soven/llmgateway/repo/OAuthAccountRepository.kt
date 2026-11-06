package dev.soven.llmgateway.repo

import dev.soven.llmgateway.db.OAuthAccountChannels
import dev.soven.llmgateway.db.OAuthAccountModels
import dev.soven.llmgateway.db.OAuthAccounts
import dev.soven.llmgateway.domain.OAuthAccountPolicy
import dev.soven.llmgateway.domain.OAuthAccountRecord
import dev.soven.llmgateway.domain.OAuthAuthState
import dev.soven.llmgateway.domain.OAuthProvider
import dev.soven.llmgateway.domain.OAuthQuotaSnapshot
import dev.soven.llmgateway.domain.OAuthQuotaWindow
import dev.soven.llmgateway.security.EncryptedCredential
import dev.soven.llmgateway.security.credentialFingerprint
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/** 刷新时同时需要账号状态和账号自身的 refresh token 密文。 */
data class OAuthRefreshContext(
    val account: OAuthAccountRecord,
    val refreshTokenCiphertext: String,
    val refreshTokenNonce: String,
    val refreshTokenKeyVersion: Int,
)

/** 跨步骤操作期间目标账号的凭据被替换，不能再写入旧流程的结果。 */
class CredentialChangedException : RuntimeException("目标账号凭据已变化")

/** 新建独立订阅账号的输入。 */
data class NewOAuthAccount(
    val name: String,
    val provider: OAuthProvider,
    val accountEmail: String? = null,
    val accountPlan: String? = null,
    val refreshToken: EncryptedCredential,
    val accessToken: EncryptedCredential? = null,
    val accessTokenExpiresAt: Long? = null,
    val tokenType: String = "Bearer",
    val scope: String? = null,
    val proxyUrl: String? = null,
    val channelIds: Collection<String> = emptyList(),
    val models: Collection<String> = emptyList(),
    val priority: Int = 1,
    val enabled: Boolean = true,
)

/** 重新授权或导入已有账号时更新的字段；refreshToken 为空表示保留原凭据。 */
data class OAuthAccountCredentialUpdate(
    val provider: OAuthProvider,
    val accountEmail: String? = null,
    val accountPlan: String? = null,
    val refreshToken: EncryptedCredential? = null,
    val accessToken: EncryptedCredential? = null,
    val accessTokenExpiresAt: Long? = null,
    val tokenType: String = "Bearer",
    val scope: String? = null,
    val proxyUrl: String? = null,
    val expectedCredentialFingerprint: String? = null,
)

/**
 * 独立订阅账号的持久化。账号凭据只读写密文，不解密、不记录任何 token。
 *
 * @author 文杰
 * @since 2026-11-06
 */
class OAuthAccountRepository(private val database: Database) {
    /** 数据面快速路径：只读账号本体，不加载模型与渠道关联。 */
    suspend fun find(id: String): OAuthAccountRecord? = suspendTransaction(database) {
        OAuthAccounts.selectAll().where { OAuthAccounts.id eq id }.singleOrNull()
            ?.let { toRecord(it, emptyList(), emptySet()) }
    }

    /** 管理面详情：同时加载账号授权的模型和关联渠道。 */
    suspend fun findDetailed(id: String): OAuthAccountRecord? = suspendTransaction(database) {
        val rows = OAuthAccounts.selectAll().where { OAuthAccounts.id eq id }.toList()
        loadRelations(rows).singleOrNull()
    }

    /** 管理面账号看板：按最近变更倒序返回全部订阅账号。 */
    suspend fun listAll(limit: Int = 5_000): List<OAuthAccountRecord> = suspendTransaction(database) {
        val rows = OAuthAccounts.selectAll()
            .orderBy(OAuthAccounts.updatedAt to SortOrder.DESC)
            .limit(limit.coerceIn(1, 20_000))
            .toList()
        loadRelations(rows)
    }

    /** 数据面候选：按渠道加载关联账号，包含停用账号，由路由阶段过滤。 */
    suspend fun listByChannel(limit: Int = 20_000): Map<String, List<OAuthAccountRecord>> = suspendTransaction(database) {
        val links = OAuthAccountChannels.selectAll()
            .limit(limit.coerceIn(1, 100_000))
            .toList()
        if (links.isEmpty()) return@suspendTransaction emptyMap()
        val accountIds = links.map { it[OAuthAccountChannels.accountId] }.distinct()
        val rows = OAuthAccounts.selectAll().where { OAuthAccounts.id inList accountIds }.toList()
        val accounts = loadRelations(rows).associateBy { it.id }
        links.groupBy { it[OAuthAccountChannels.channelId] }.mapValues { (_, channelLinks) ->
            channelLinks.mapNotNull { accounts[it[OAuthAccountChannels.accountId]] }
        }
    }

    /** 后台扫描：只返回仍可自动刷新、且已进入提前刷新窗口的账号。 */
    suspend fun listDueForRefresh(dueBefore: Long, limit: Int = 100): List<OAuthAccountRecord> =
        suspendTransaction(database) {
            OAuthAccounts.selectAll()
                .where {
                    (OAuthAccounts.authState eq OAuthAuthState.ACTIVE.value) and
                        (OAuthAccounts.enabled eq true) and
                        (
                            OAuthAccounts.accessTokenExpiresAt.isNull() or
                                (OAuthAccounts.accessTokenExpiresAt less dueBefore)
                            )
                }
                .orderBy(OAuthAccounts.accessTokenExpiresAt to SortOrder.ASC_NULLS_FIRST)
                .limit(limit.coerceIn(1, 1_000))
                .map { toRecord(it, emptyList(), emptySet()) }
        }

    /**
     * 后台额度扫描：只返回仍可自动刷新、且距上次尝试已过期的 ChatGPT 账号。
     *
     * 排序用“最近一次尝试”而不是“最近一次成功读数”，否则持续失败的账号会一直排在
     * 队首、每轮被重新选中，账号数超过一轮上限时会把健康账号饿死。
     */
    suspend fun listDueForQuota(attemptedBefore: Long, limit: Int = 100): List<OAuthAccountRecord> =
        suspendTransaction(database) {
            OAuthAccounts.selectAll()
                .where {
                    (OAuthAccounts.authState eq OAuthAuthState.ACTIVE.value) and
                        (OAuthAccounts.enabled eq true) and
                        (OAuthAccounts.provider eq OAuthProvider.CHATGPT.value) and
                        (
                            OAuthAccounts.quotaAttemptedAt.isNull() or
                                (OAuthAccounts.quotaAttemptedAt less attemptedBefore)
                            )
                }
                .orderBy(OAuthAccounts.quotaAttemptedAt to SortOrder.ASC_NULLS_FIRST)
                .limit(limit.coerceIn(1, 1_000))
                .map { toRecord(it, emptyList(), emptySet()) }
        }

    suspend fun create(
        input: NewOAuthAccount,
        now: Long = System.currentTimeMillis(),
    ): OAuthAccountRecord = suspendTransaction(database) {
        require((input.accessToken == null) == (input.accessTokenExpiresAt == null)) {
            "access token and expiry must be provided together"
        }
        val id = UUID.randomUUID().toString()
        OAuthAccounts.insert {
            it[OAuthAccounts.id] = id
            it[name] = input.name.take(100)
            it[provider] = input.provider.value
            it[accountEmail] = input.accountEmail
            it[accountPlan] = input.accountPlan
            it[refreshTokenCiphertext] = input.refreshToken.ciphertext
            it[refreshTokenNonce] = input.refreshToken.nonce
            it[refreshTokenKeyVersion] = input.refreshToken.keyVersion
            it[accessTokenCiphertext] = input.accessToken?.ciphertext
            it[accessTokenNonce] = input.accessToken?.nonce
            it[accessTokenKeyVersion] = input.accessToken?.keyVersion
            it[accessTokenExpiresAt] = input.accessTokenExpiresAt
            it[tokenType] = input.tokenType.take(32)
            it[scope] = input.scope
            it[authState] = OAuthAuthState.ACTIVE.value
            it[enabled] = input.enabled
            it[priority] = input.priority.coerceIn(1, 1_000)
            it[refreshFailureCount] = 0
            it[lastRefreshError] = null
            it[lastRefreshedAt] = if (input.accessToken != null) now else null
            it[proxyUrl] = input.proxyUrl
            it[windowLimit] = null
            it[windowSeconds] = 10_800
            it[maxConcurrentRequests] = 1
            it[createdAt] = now
            it[OAuthAccounts.updatedAt] = now
        }
        replaceChannels(id, input.channelIds, now)
        replaceModels(id, input.models, now)
        requireNotNull(findDetailedInTransaction(id))
    }

    /**
     * 重新授权或导入账号凭据。只有传入 refreshToken 才会替换长期凭据；
     * access token 未提供时清空旧缓存，避免把旧账号的 token 用到新凭据上。
     */
    suspend fun updateCredentials(
        accountId: String,
        input: OAuthAccountCredentialUpdate,
        now: Long = System.currentTimeMillis(),
    ): OAuthAccountRecord? = suspendTransaction(database) {
        require((input.accessToken == null) == (input.accessTokenExpiresAt == null)) {
            "access token and expiry must be provided together"
        }
        val row = OAuthAccounts.selectAll()
            .where { OAuthAccounts.id eq accountId }
            .forUpdate()
            .singleOrNull() ?: return@suspendTransaction null
        val currentProvider = OAuthProvider.fromValue(row[OAuthAccounts.provider])
            ?: throw IllegalStateException("未知的 OAuth provider: ${row[OAuthAccounts.provider]}")
        require(currentProvider == input.provider) { "不能修改订阅账号的提供方" }
        if (input.expectedCredentialFingerprint != null) {
            val current = credentialFingerprint(
                row[OAuthAccounts.refreshTokenCiphertext],
                row[OAuthAccounts.refreshTokenNonce],
                row[OAuthAccounts.refreshTokenKeyVersion],
            )
            if (current != input.expectedCredentialFingerprint) throw CredentialChangedException()
        }
        OAuthAccounts.update({ OAuthAccounts.id eq accountId }) {
            it[provider] = input.provider.value
            it[accountEmail] = input.accountEmail
            it[accountPlan] = input.accountPlan
            input.refreshToken?.let { refresh ->
                it[refreshTokenCiphertext] = refresh.ciphertext
                it[refreshTokenNonce] = refresh.nonce
                it[refreshTokenKeyVersion] = refresh.keyVersion
            }
            it[accessTokenCiphertext] = input.accessToken?.ciphertext
            it[accessTokenNonce] = input.accessToken?.nonce
            it[accessTokenKeyVersion] = input.accessToken?.keyVersion
            it[accessTokenExpiresAt] = input.accessTokenExpiresAt
            it[tokenType] = input.tokenType.take(32)
            it[scope] = input.scope
            it[authState] = OAuthAuthState.ACTIVE.value
            it[refreshFailureCount] = 0
            it[lastRefreshError] = null
            it[lastRefreshedAt] = if (input.accessToken != null) now else null
            it[proxyUrl] = input.proxyUrl
            it[OAuthAccounts.updatedAt] = now
        }
        findDetailedInTransaction(accountId)
    }

    suspend fun updateSettings(
        accountId: String,
        name: String? = null,
        priority: Int? = null,
        enabled: Boolean? = null,
        proxyUrl: String? = null,
        clearProxy: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): OAuthAccountRecord? = suspendTransaction(database) {
        require(!(clearProxy && proxyUrl != null))
        val changed = OAuthAccounts.update({ OAuthAccounts.id eq accountId }) {
            name?.let { value -> it[OAuthAccounts.name] = value.take(100) }
            priority?.let { value -> it[OAuthAccounts.priority] = value.coerceIn(1, 1_000) }
            enabled?.let { value -> it[OAuthAccounts.enabled] = value }
            if (clearProxy) it[OAuthAccounts.proxyUrl] = null
            proxyUrl?.let { value -> it[OAuthAccounts.proxyUrl] = value }
            it[OAuthAccounts.updatedAt] = now
        }
        if (changed == 0) null else findDetailedInTransaction(accountId)
    }

    suspend fun updatePolicy(
        accountId: String,
        policy: OAuthAccountPolicy,
        now: Long = System.currentTimeMillis(),
    ): Boolean = suspendTransaction(database) {
        OAuthAccounts.update({ OAuthAccounts.id eq accountId }) {
            it[windowLimit] = policy.windowLimit
            it[windowSeconds] = policy.windowSeconds
            it[maxConcurrentRequests] = policy.maxConcurrentRequests
            it[OAuthAccounts.updatedAt] = now
        } > 0
    }

    suspend fun setChannels(
        accountId: String,
        channelIds: Collection<String>,
        now: Long = System.currentTimeMillis(),
    ): OAuthAccountRecord? = suspendTransaction(database) {
        if (OAuthAccounts.selectAll().where { OAuthAccounts.id eq accountId }.empty()) {
            return@suspendTransaction null
        }
        replaceChannels(accountId, channelIds, now)
        OAuthAccounts.update({ OAuthAccounts.id eq accountId }) { it[OAuthAccounts.updatedAt] = now }
        findDetailedInTransaction(accountId)
    }

    suspend fun setModels(
        accountId: String,
        models: Collection<String>,
        now: Long = System.currentTimeMillis(),
    ): OAuthAccountRecord? = suspendTransaction(database) {
        if (OAuthAccounts.selectAll().where { OAuthAccounts.id eq accountId }.empty()) {
            return@suspendTransaction null
        }
        replaceModels(accountId, models, now)
        OAuthAccounts.update({ OAuthAccounts.id eq accountId }) { it[OAuthAccounts.updatedAt] = now }
        findDetailedInTransaction(accountId)
    }

    suspend fun delete(accountId: String): Boolean = suspendTransaction(database) {
        OAuthAccounts.deleteWhere { OAuthAccounts.id eq accountId } > 0
    }

    /** 读取一次刷新所需的全部输入。 */
    suspend fun loadRefreshContext(accountId: String): OAuthRefreshContext? = suspendTransaction(database) {
        val row = OAuthAccounts.selectAll()
            .where { OAuthAccounts.id eq accountId }
            .singleOrNull() ?: return@suspendTransaction null
        OAuthRefreshContext(
            account = toRecord(row, emptyList(), emptySet()),
            refreshTokenCiphertext = row[OAuthAccounts.refreshTokenCiphertext],
            refreshTokenNonce = row[OAuthAccounts.refreshTokenNonce],
            refreshTokenKeyVersion = row[OAuthAccounts.refreshTokenKeyVersion],
        )
    }

    /**
     * 保存一次成功刷新。传入 expectedRefreshCiphertext 时先锁账号行并校验凭据未被替换；
     * 刷新轮换出的新 refresh token 与 access token 在同一事务写入。
     */
    suspend fun saveRefreshed(
        accountId: String,
        accessToken: EncryptedCredential,
        expiresAt: Long,
        tokenType: String,
        scope: String?,
        rotatedRefreshToken: EncryptedCredential? = null,
        expectedRefreshCiphertext: String? = null,
        now: Long = System.currentTimeMillis(),
    ): OAuthAccountRecord? = suspendTransaction(database) {
        val row = OAuthAccounts.selectAll()
            .where { OAuthAccounts.id eq accountId }
            .forUpdate()
            .singleOrNull() ?: return@suspendTransaction null
        if (expectedRefreshCiphertext != null && row[OAuthAccounts.refreshTokenCiphertext] != expectedRefreshCiphertext) {
            return@suspendTransaction null
        }
        val updated = OAuthAccounts.update({ OAuthAccounts.id eq accountId }) {
            it[accessTokenCiphertext] = accessToken.ciphertext
            it[accessTokenNonce] = accessToken.nonce
            it[accessTokenKeyVersion] = accessToken.keyVersion
            it[accessTokenExpiresAt] = expiresAt
            it[OAuthAccounts.tokenType] = tokenType.take(32)
            it[OAuthAccounts.scope] = scope
            it[authState] = OAuthAuthState.ACTIVE.value
            it[refreshFailureCount] = 0
            it[lastRefreshError] = null
            it[lastRefreshedAt] = now
            it[OAuthAccounts.updatedAt] = now
            rotatedRefreshToken?.let { refresh ->
                it[refreshTokenCiphertext] = refresh.ciphertext
                it[refreshTokenNonce] = refresh.nonce
                it[refreshTokenKeyVersion] = refresh.keyVersion
            }
        }
        if (updated == 0) return@suspendTransaction null
        OAuthAccounts.selectAll()
            .where { OAuthAccounts.id eq accountId }
            .single()
            .let { toRecord(it, emptyList(), emptySet()) }
    }

    /**
     * 记录一次刷新失败；终态错误同时把账号标记为需要重新授权。
     * 传入 expectedRefreshCiphertext 时先锁账号行并校验凭据未被替换。
     */
    suspend fun recordFailure(
        accountId: String,
        errorCode: String,
        terminal: Boolean,
        expectedRefreshCiphertext: String? = null,
        now: Long = System.currentTimeMillis(),
    ): OAuthAccountRecord? = suspendTransaction(database) {
        val row = OAuthAccounts.selectAll()
            .where { OAuthAccounts.id eq accountId }
            .forUpdate()
            .singleOrNull() ?: return@suspendTransaction null
        if (expectedRefreshCiphertext != null && row[OAuthAccounts.refreshTokenCiphertext] != expectedRefreshCiphertext) {
            return@suspendTransaction null
        }
        val failureCount = row[OAuthAccounts.refreshFailureCount] + 1
        OAuthAccounts.update({ OAuthAccounts.id eq accountId }) {
            it[refreshFailureCount] = failureCount
            it[lastRefreshError] = sanitizeErrorCode(errorCode)
            if (terminal) it[authState] = OAuthAuthState.REAUTH_REQUIRED.value
            it[OAuthAccounts.updatedAt] = now
        }
        OAuthAccounts.selectAll()
            .where { OAuthAccounts.id eq accountId }
            .single()
            .let { toRecord(it, emptyList(), emptySet()) }
    }

    /** 从 access token 解析出的 ChatGPT 账号 id，刷新成功后写入。 */
    suspend fun updateChatgptAccountId(accountId: String, chatgptAccountId: String): Boolean = suspendTransaction(database) {
        OAuthAccounts.update({
            (OAuthAccounts.id eq accountId) and
                (OAuthAccounts.chatgptAccountId.isNull() or (OAuthAccounts.chatgptAccountId neq chatgptAccountId))
        }) {
            it[OAuthAccounts.chatgptAccountId] = chatgptAccountId.take(128)
        } > 0
    }

    /** 保存一次成功的额度读数；`accountPlan` 非空时同步上游给出的套餐名。 */
    suspend fun saveQuota(
        accountId: String,
        snapshot: OAuthQuotaSnapshot,
        accountPlan: String? = null,
    ): Boolean = suspendTransaction(database) {
        OAuthAccounts.update({ OAuthAccounts.id eq accountId }) {
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
            it[quotaAttemptedAt] = snapshot.checkedAt
            it[quotaError] = null
            if (accountPlan != null) it[OAuthAccounts.accountPlan] = accountPlan.take(32)
        } > 0
    }

    /**
     * 记录一次额度读取失败；保留上一次成功读数与它的新鲜度时间，只更新错误信息、
     * 并推进“最近一次尝试”，让这轮扫描不会反复选中同一个失败账号。
     */
    suspend fun saveQuotaError(
        accountId: String,
        errorCode: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean = suspendTransaction(database) {
        OAuthAccounts.update({ OAuthAccounts.id eq accountId }) {
            it[quotaError] = sanitizeQuotaError(errorCode)
            it[quotaAttemptedAt] = now
        } > 0
    }

    private fun findDetailedInTransaction(accountId: String): OAuthAccountRecord? {
        val rows = OAuthAccounts.selectAll().where { OAuthAccounts.id eq accountId }.toList()
        return loadRelations(rows).singleOrNull()
    }

    private fun loadRelations(rows: List<ResultRow>): List<OAuthAccountRecord> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it[OAuthAccounts.id] }
        val modelsByAccount = OAuthAccountModels.selectAll()
            .where { OAuthAccountModels.accountId inList ids }
            .groupBy { it[OAuthAccountModels.accountId] }
            .mapValues { (_, models) -> models.map { it[OAuthAccountModels.upstreamModel] }.sorted() }
        val channelsByAccount = OAuthAccountChannels.selectAll()
            .where { OAuthAccountChannels.accountId inList ids }
            .groupBy { it[OAuthAccountChannels.accountId] }
            .mapValues { (_, links) -> links.map { it[OAuthAccountChannels.channelId] }.toSet() }
        return rows.map { row ->
            toRecord(
                row,
                modelsByAccount[row[OAuthAccounts.id]].orEmpty(),
                channelsByAccount[row[OAuthAccounts.id]].orEmpty(),
            )
        }
    }

    private fun replaceChannels(accountId: String, channelIds: Collection<String>, now: Long) {
        OAuthAccountChannels.deleteWhere { OAuthAccountChannels.accountId eq accountId }
        channelIds.map(String::trim).filter(String::isNotEmpty).distinct().forEach { channelId ->
            OAuthAccountChannels.insert {
                it[OAuthAccountChannels.accountId] = accountId
                it[OAuthAccountChannels.channelId] = channelId
                it[createdAt] = now
            }
        }
    }

    private fun replaceModels(accountId: String, models: Collection<String>, now: Long) {
        OAuthAccountModels.deleteWhere { OAuthAccountModels.accountId eq accountId }
        models.map(String::trim).filter(String::isNotEmpty).distinct().forEach { model ->
            OAuthAccountModels.insert {
                it[OAuthAccountModels.accountId] = accountId
                it[upstreamModel] = model.take(200)
                it[createdAt] = now
            }
        }
    }

    private fun toRecord(
        row: ResultRow,
        models: List<String>,
        channelIds: Set<String>,
    ) = OAuthAccountRecord(
        id = row[OAuthAccounts.id],
        name = row[OAuthAccounts.name],
        provider = OAuthProvider.fromValue(row[OAuthAccounts.provider])
            ?: throw IllegalStateException("未知的 OAuth provider: ${row[OAuthAccounts.provider]}"),
        accountEmail = row[OAuthAccounts.accountEmail],
        accountPlan = row[OAuthAccounts.accountPlan],
        refreshTokenCiphertext = row[OAuthAccounts.refreshTokenCiphertext],
        refreshTokenNonce = row[OAuthAccounts.refreshTokenNonce],
        refreshTokenKeyVersion = row[OAuthAccounts.refreshTokenKeyVersion],
        accessTokenCiphertext = row[OAuthAccounts.accessTokenCiphertext],
        accessTokenNonce = row[OAuthAccounts.accessTokenNonce],
        accessTokenKeyVersion = row[OAuthAccounts.accessTokenKeyVersion],
        accessTokenExpiresAt = row[OAuthAccounts.accessTokenExpiresAt],
        tokenType = row[OAuthAccounts.tokenType],
        scope = row[OAuthAccounts.scope],
        authState = OAuthAuthState.fromValue(row[OAuthAccounts.authState])
            ?: throw IllegalStateException("未知的 OAuth 账号状态: ${row[OAuthAccounts.authState]}"),
        enabled = row[OAuthAccounts.enabled],
        priority = row[OAuthAccounts.priority],
        models = models,
        channelIds = channelIds,
        refreshFailureCount = row[OAuthAccounts.refreshFailureCount],
        lastRefreshError = row[OAuthAccounts.lastRefreshError],
        lastRefreshedAt = row[OAuthAccounts.lastRefreshedAt],
        proxyUrl = row[OAuthAccounts.proxyUrl],
        policy = OAuthAccountPolicy(
            windowLimit = row[OAuthAccounts.windowLimit],
            windowSeconds = row[OAuthAccounts.windowSeconds],
            maxConcurrentRequests = row[OAuthAccounts.maxConcurrentRequests],
        ),
        chatgptAccountId = row[OAuthAccounts.chatgptAccountId],
        quota = toQuota(row),
        createdAt = row[OAuthAccounts.createdAt],
        updatedAt = row[OAuthAccounts.updatedAt],
    )

    private fun toQuota(row: ResultRow): OAuthQuotaSnapshot? {
        val checkedAt = row[OAuthAccounts.quotaCheckedAt]
        val error = row[OAuthAccounts.quotaError]
        if (checkedAt == null && error == null) return null
        return OAuthQuotaSnapshot(
            primary = quotaWindow(
                row[OAuthAccounts.quotaPrimaryUsedPercent],
                row[OAuthAccounts.quotaPrimaryWindowSeconds],
                row[OAuthAccounts.quotaPrimaryResetsAt],
            ),
            secondary = quotaWindow(
                row[OAuthAccounts.quotaSecondaryUsedPercent],
                row[OAuthAccounts.quotaSecondaryWindowSeconds],
                row[OAuthAccounts.quotaSecondaryResetsAt],
            ),
            allowed = row[OAuthAccounts.quotaAllowed],
            creditsBalance = row[OAuthAccounts.quotaCreditsBalance],
            creditsUnlimited = row[OAuthAccounts.quotaCreditsUnlimited],
            resetCredits = row[OAuthAccounts.quotaResetCredits],
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
