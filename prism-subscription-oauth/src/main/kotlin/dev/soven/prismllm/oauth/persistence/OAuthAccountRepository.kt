package dev.soven.prismllm.oauth.persistence

import dev.soven.prismllm.oauth.domain.OAuthAccountModelCatalogEntry
import dev.soven.prismllm.oauth.domain.OAuthAccountPolicy
import dev.soven.prismllm.oauth.domain.OAuthAccountRecord
import dev.soven.prismllm.oauth.domain.OAuthAuthState
import dev.soven.prismllm.shared.domain.OAuthProvider
import dev.soven.prismllm.oauth.domain.OAuthQuotaGroup
import dev.soven.prismllm.oauth.domain.OAuthQuotaSnapshot
import dev.soven.prismllm.oauth.domain.OAuthQuotaWindow
import dev.soven.prismllm.shared.crypto.EncryptedCredential
import dev.soven.prismllm.shared.crypto.credentialFingerprint
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.notInList
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
    /** 可服务的 provider 先标记待关联，挂到系统渠道后再清除；中途失败可由启动对账修复。 */
    val providerChannelPending: Boolean = false,
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
open class OAuthAccountRepository(
    private val database: Database,
    private val providerChannels: ProviderChannelLookup,
) {
    /** 额度分组只用于展示，字段演进时忽略未知键，避免旧读数读不出来。 */
    private val quotaJson = Json { ignoreUnknownKeys = true }

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

    /**
     * 指定 provider 下待改挂到系统渠道的账号。按 provider 过滤，避免协议未实现的
     * 账号长期占满批次、把可服务账号饿死；逐个搬迁，失败的下次启动继续。
     */
    suspend fun listPendingProviderChannel(
        providers: Collection<OAuthProvider>,
        limit: Int = 5_000,
    ): List<OAuthAccountRecord> = suspendTransaction(database) {
        if (providers.isEmpty()) return@suspendTransaction emptyList()
        val rows = OAuthAccounts.selectAll()
            .where {
                (OAuthAccounts.providerChannelPending eq true) and
                    (OAuthAccounts.provider inList providers.map { it.value })
            }
            .orderBy(OAuthAccounts.createdAt to SortOrder.ASC)
            .limit(limit.coerceIn(1, 20_000))
            .toList()
        loadRelations(rows)
    }

    /** 账号已改挂到系统渠道；与改挂分开提交，重试时幂等。 */
    suspend fun markProviderChannelSettled(accountId: String): Boolean = suspendTransaction(database) {
        OAuthAccounts.update({ OAuthAccounts.id eq accountId }) {
            it[providerChannelPending] = false
        } > 0
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
     * 后台额度扫描：返回仍可自动刷新、且距上次尝试已过期的订阅账号。
     *
     * ChatGPT 与 Antigravity 都支持额度读取；后者需要账号先在 Code Assist
     * 完成开通，未开通时扫描会记录 project_missing 并等待下一轮重试。
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
                        (OAuthAccounts.provider inList listOf(
                            OAuthProvider.CHATGPT.value,
                            OAuthProvider.ANTIGRAVITY.value,
                        )) and
                        (
                            OAuthAccounts.quotaAttemptedAt.isNull() or
                                (OAuthAccounts.quotaAttemptedAt less attemptedBefore)
                            )
                }
                .orderBy(OAuthAccounts.quotaAttemptedAt to SortOrder.ASC_NULLS_FIRST)
                .limit(limit.coerceIn(1, 1_000))
                .map { toRecord(it, emptyList(), emptySet()) }
        }

    /**
     * 指定 provider 下可用于读取模型目录的账号：只取启用且仍是 ACTIVE 的账号，
     * 健康账号排在前面，避免一个待重新授权的账号把管理面操作卡死。
     */
    suspend fun listUsableForProvider(provider: OAuthProvider, limit: Int = 100): List<OAuthAccountRecord> =
        suspendTransaction(database) {
            OAuthAccounts.selectAll()
                .where {
                    (OAuthAccounts.provider eq provider.value) and
                        (OAuthAccounts.enabled eq true) and
                        (OAuthAccounts.authState eq OAuthAuthState.ACTIVE.value)
                }
                // 状态条件必须在 limit 之前生效，否则前面的不可用账号会把后面的健康账号挤掉。
                .orderBy(OAuthAccounts.updatedAt to SortOrder.DESC)
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
            it[providerChannelPending] = input.providerChannelPending
            it[refreshFailureCount] = 0
            it[lastRefreshError] = null
            it[lastRefreshedAt] = if (input.accessToken != null) now else null
            it[proxyUrl] = input.proxyUrl
            it[windowLimit] = null
            it[windowSeconds] = 10_800
            it[maxConcurrentRequests] = 1
            it[createdAt] = now
            it[OAuthAccounts.updatedAt] = now
            it[authorizedAt] = now
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
                // 换成新的 refresh token 才算一次新的授权；只更新 access token 不改变授权时间。
                it[authorizedAt] = now
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

    /**
     * 保存账号自己的上游模型目录。目录按账号隔离：不同开通方式拿到的模型不同，
     * 不能用某个账号的结果覆盖共享渠道目录。
     */
    suspend fun saveModelCatalog(
        accountId: String,
        catalog: List<OAuthAccountModelCatalogEntry>,
        now: Long = System.currentTimeMillis(),
    ): Boolean = suspendTransaction(database) {
        val entries = catalog
            .mapNotNull { entry ->
                val upstreamModel = entry.upstreamModel.trim()
                if (upstreamModel.isEmpty()) null
                else OAuthAccountModelCatalogEntry(
                    upstreamModel = upstreamModel.take(MAX_CATALOG_MODEL_ID),
                    canonicalModelId = entry.canonicalModelId?.trim()?.take(MAX_CATALOG_MODEL_ID),
                )
            }
            .distinctBy { it.upstreamModel }
            .take(MAX_CATALOG_MODELS)
        OAuthAccounts.update({ OAuthAccounts.id eq accountId }) {
            it[modelCatalog] = quotaJson.encodeToString(entries)
            it[modelCatalogSyncedAt] = now
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

    /**
     * 目录同步后的授权收敛：在同一事务里读取当前授权行，只删除目录外的行，
     * 保留管理员在目录抓取期间的并发改动。
     *
     * 删除后如果一行不剩，说明「显式授权」的模型全部不在新目录里——这不等于「跟随目录」，
     * 不能让账号继承整个目录。用一条显式零授权哨兵行（目录里不可能存在的名字）占住
     * 「models 非空」状态：supports() 只匹配该哨兵，任何真实模型都会被拒绝，
     * 管理接口的授权勾选也会看到它并允许管理员重新选择。
     */
    suspend fun removeModelsOutsideCatalog(
        accountId: String,
        catalog: Collection<String>,
        now: Long = System.currentTimeMillis(),
    ): Boolean = suspendTransaction(database) {
        if (OAuthAccounts.selectAll().where { OAuthAccounts.id eq accountId }.empty()) {
            return@suspendTransaction false
        }
        val allowed = catalog.map(String::trim).filter(String::isNotEmpty).toSet()
        val deleted = OAuthAccountModels.deleteWhere {
            (OAuthAccountModels.accountId eq accountId) and
                (OAuthAccountModels.upstreamModel notInList allowed)
        }
        val remaining = OAuthAccountModels.selectAll()
            .where { OAuthAccountModels.accountId eq accountId }
            .count()
        // 仅当本轮真的删掉了目录外的行且一行不剩时才写哨兵：管理员并发改成「跟随目录」
        // （先把 models 清空）时没有行可删，remaining 本来就是 0，不写哨兵。
        if (deleted > 0 && remaining == 0L) {
            OAuthAccountModels.insert {
                it[OAuthAccountModels.accountId] = accountId
                it[upstreamModel] = EXPLICIT_EMPTY_MODELS_SENTINEL
                it[createdAt] = now
            }
        }
        OAuthAccounts.update({ OAuthAccounts.id eq accountId }) { it[OAuthAccounts.updatedAt] = now }
        true
    }

    companion object {
        /**
         * 「显式授权但目录里已无可用模型」的哨兵行。目录同步只会写入真实模型名，
         * 且该名字不可能是合法模型 id，因此绝不会与目录或真实授权冲突。
         */
        private const val EXPLICIT_EMPTY_MODELS_SENTINEL = "(no models available)"
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

    /** Code Assist 的上游上下文：cloud project 与当前 tier。 */
    data class UpstreamContext(
        val project: String?,
        val tier: String?,
        val checkedAt: Long?,
    )

    open suspend fun upstreamContext(accountId: String): UpstreamContext? = loadUpstreamContext(accountId)

    open suspend fun saveUpstreamContext(
        accountId: String,
        project: String?,
        tier: String?,
        checkedAt: Long,
    ): Boolean = persistUpstreamContext(accountId, project, tier, checkedAt)

    open suspend fun loadUpstreamContext(accountId: String): UpstreamContext? = suspendTransaction(database) {
        OAuthAccounts.selectAll()
            .where { OAuthAccounts.id eq accountId }
            .singleOrNull()
            ?.let { row ->
            UpstreamContext(
                project = row[OAuthAccounts.upstreamProject],
                tier = row[OAuthAccounts.upstreamTier],
                checkedAt = row[OAuthAccounts.upstreamTierCheckedAt],
            )
        }
    }

    /**
     * 写入一次协商结果。`tier` 为 null 表示上游这次没给出套餐信息，此时显式清空旧值，
     * 而不是留下上一次的 tier：内存中的协商状态与数据库必须一致，否则进程重启后会读到
     * 一个本次请求并不认可的 tier（及其对应的模型目录映射）。
     */
    open suspend fun persistUpstreamContext(
        accountId: String,
        project: String?,
        tier: String?,
        checkedAt: Long,
    ): Boolean = suspendTransaction(database) {
        OAuthAccounts.update({ OAuthAccounts.id eq accountId }) {
            if (project != null) it[OAuthAccounts.upstreamProject] = project.take(128)
            it[OAuthAccounts.upstreamTier] = tier?.take(64)
            it[OAuthAccounts.upstreamTierCheckedAt] = checkedAt
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
            it[quotaGroups] = snapshot.groups
                // 分组名与窗口来自上游，编码前先限量，避免异常响应把 JSON 撑大后截断成非法内容。
                .take(MAX_QUOTA_GROUPS)
                .map { group ->
                    group.copy(
                        name = group.name.take(MAX_QUOTA_GROUP_NAME),
                        windows = group.windows.take(MAX_QUOTA_GROUP_WINDOWS),
                    )
                }
                .takeIf { groups -> groups.isNotEmpty() }
                ?.let { groups -> quotaJson.encodeToString(groups) }
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
        val ids = channelIds.map(String::trim).filter(String::isNotEmpty).distinct()
        // 托管标记决定账号是否受 provider 渠道约束；只有挂在系统渠道上才算托管。
        val managedIds = if (ids.isEmpty()) {
            emptySet()
        } else {
            providerChannels.managedChannelIds(ids)
        }
        ids.forEach { channelId ->
            OAuthAccountChannels.insert {
                it[OAuthAccountChannels.accountId] = accountId
                it[OAuthAccountChannels.channelId] = channelId
                it[managed] = channelId in managedIds
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
        modelCatalog = toModelCatalog(row),
        modelCatalogSyncedAt = row[OAuthAccounts.modelCatalogSyncedAt],
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
        authorizedAt = row[OAuthAccounts.authorizedAt],
    )

    private fun toModelCatalog(row: ResultRow): List<OAuthAccountModelCatalogEntry>? =
        row[OAuthAccounts.modelCatalog]
            ?.let { raw ->
                runCatching {
                    quotaJson.decodeFromString<List<OAuthAccountModelCatalogEntry>>(raw)
                }.getOrNull()
            }

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
            groups = row[OAuthAccounts.quotaGroups]
                ?.let { raw -> runCatching { quotaJson.decodeFromString<List<OAuthQuotaGroup>>(raw) }.getOrNull() }
                .orEmpty(),
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

/**
 * 分组额度的存储上限。上游目前只给两个模型家族，正常读数远小于这些上限；
 * 限量是为了让异常响应也能编码成合法 JSON，界面最多展示前几组。
 */
private const val MAX_CATALOG_MODELS = 500
private const val MAX_CATALOG_MODEL_ID = 200
private const val MAX_QUOTA_GROUPS = 16
private const val MAX_QUOTA_GROUP_WINDOWS = 8
private const val MAX_QUOTA_GROUP_NAME = 100
