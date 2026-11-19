package dev.soven.prismllm.proxy.codeassist

import dev.soven.prismllm.db.DiagnosticPreparationDeadline
import dev.soven.prismllm.oauth.domain.OAuthAccountRecord
import dev.soven.prismllm.oauth.persistence.OAuthAccountRepository
import dev.soven.prismllm.oauth.OAuthUnavailableReason
import dev.soven.prismllm.oauth.OAuthTokenUnavailableException
import dev.soven.prismllm.oauth.matchesCredentialSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.ContentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Antigravity 的 Code Assist 需要每个账号自己的 cloud project 与 tier：
 * loadCodeAssist 发现，必要时 onboardUser 首次开通，结果缓存一小时。
 * 对应 magpie 的 antigravityProject / antigravityTiers。
 *
 * @author 文杰
 * @since 2026-11-06
 */
class CodeAssistProjectResolver(
    private val accounts: OAuthAccountRepository,
    private val httpClient: HttpClient,
    private val baseUrl: String = "https://daily-cloudcode-pa.googleapis.com",
    private val loadBaseUrl: String = "https://cloudcode-pa.googleapis.com",
    private val ideVersion: String = "2.29.1",
    private val now: () -> Long = System::currentTimeMillis,
    /** 校验账号代理地址的允许主机列表；与 token 刷新使用同一份配置。 */
    private val proxyAllowedHosts: Set<String> = emptySet(),
    /** 账号代理：project 发现与开通必须与 token 刷新走同一个出口。 */
    private val clients: CodeAssistClientPool = CodeAssistClientPool(httpClient),
    /** 当前 Antigravity 客户端 UA；由全局请求头目录提供，目录不可用时退回内置值。 */
    private val userAgent: () -> String = { DEFAULT_ANTIGRAVITY_CODE_ASSIST_USER_AGENT },
) {
    private val logger = LoggerFactory.getLogger(CodeAssistProjectResolver::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /** 数据面的 Code Assist 上下文：project，以及目录与资格判断使用的 tier（付费档优先）。 */
    data class Context(val project: String?, val tier: String?)

    /** 模型目录探测结果：付费档优先，其次当前档；允许档只用于首次开通。 */
    data class Probe(
        val project: String?,
        val tier: String?,
        val allowedTiers: List<String> = emptyList(),
        val paidTier: String? = null,
    ) {
        /**
         * fetchAvailableModels 的 entitlement 列表：付费档优先，其次当前档；
         * Google 不接受在目录请求里显式命名 free/legacy 档，因此这两档留给无档位兜底。
         */
        fun tiers(): List<String> = catalogTiers(tier, paidTier)
    }

    /**
     * 上一次成功协商 project 时的出口；用于在代理变化时作废缓存。
     * 值为 [Export]，同时保存该出口下拿到的 project/tier：发现失败时只复用同一出口
     * 协商出来的上下文，绝不把旧出口的 project 带到新出口上。
     */
    private val negotiated = ConcurrentHashMap<String, Export>()

    private class Export(val account: OAuthAccountRecord, val egress: String, val project: String?, val tier: String?)

    private fun credentialReplaced() = OAuthTokenUnavailableException(
        OAuthUnavailableReason.REFRESH_FAILED,
        "订阅账号凭据已被替换，请重试",
        "credential_replaced",
    )

    /**
     * 缓存键 = 账号 + 出口。代理地址（或允许主机白名单）一变，键就变，旧缓存立即失效；
     * 用绝对地址而不是原始字符串，本地端口 80 的两种写法视为同一出口。
     */
    private fun egressKey(proxy: InetSocketAddress?): String =
        proxy?.let { it.hostString + ":" + it.port } ?: DIRECT

    /**
     * @param proxy 账号出口代理；与刷新 access token 使用同一个，避免两套 IP。
     */
    suspend fun context(account: OAuthAccountRecord, accessToken: String, proxy: InetSocketAddress? = null): Context =
        resolveContext(account, accessToken, proxy, preparation = null)

    internal suspend fun contextForDiagnostic(
        account: OAuthAccountRecord,
        accessToken: String,
        proxy: InetSocketAddress?,
        preparation: DiagnosticPreparationDeadline.Budget,
    ): Context = resolveContext(account, accessToken, proxy, preparation)

    private suspend fun resolveContext(
        account: OAuthAccountRecord,
        accessToken: String,
        proxy: InetSocketAddress?,
        preparation: DiagnosticPreparationDeadline.Budget?,
    ): Context {
        val proxyKey = egressKey(proxy)
        val export = negotiated[account.id]?.takeIf { it.account.matchesCredentialSnapshot(account) }
        val cached = databaseStep(preparation) { accounts.upstreamContext(account.id) }
        // The memory and DB records must agree before the DB value can be treated as a fast-path hit.
        // A diagnostic write can commit and then time out during cleanup before memory publication.
        val cacheMatchesExport = export != null && export.egress == proxyKey &&
            cached?.project == export.project && cached?.tier == export.tier
        val checkedAt = cached?.checkedAt
        val fresh = cached?.project != null && checkedAt != null && now() - checkedAt < TIER_TTL_MS
        if (cacheMatchesExport && fresh) return Context(export!!.project, export.tier)

        val loaded = attempt(account.id, "loadCodeAssist") { loadCodeAssist(account.id, accessToken, proxy) }
            ?: return if (cacheMatchesExport) Context(export?.project, export?.tier) else Context(null, null)
        val project = loaded.project ?: attempt(account.id, "onboardUser") {
            onboard(accessToken, loaded.tier ?: loaded.allowedTiers.firstOrNull(), proxy)
        }
        if (project == null) {
            // Do not reuse a memory context when the persisted snapshot disagrees with it.
            return if (cacheMatchesExport) Context(export?.project, export?.tier) else Context(null, null)
        }
        if (export?.egress != proxyKey) {
            logger.info("account proxy changed, renegotiated code assist context, accountId={}", account.id)
        }
        // Publish to memory only after the repository confirms this is still the exact OAuth snapshot.
        return recordContext(account, proxyKey, project, loaded.catalogTier(), loaded.reportsTier(), preparation)
            ?: Context(null, null)
    }

    /**
     * 记录一次成功的上下文协商。上游这次没给 tier 时沿用本出口已协商的 tier，并把合并后的
     * 结果同时写进数据库与内存：只写一个会让重启前后的行为不一致（数据库留着旧 tier，
     * 进程内却是 null，或反过来）。
     *
     * @param tierReported 上游是否报告了档位。免费档也会报告，但过滤后 catalog tier 为 null：
     * 此时必须覆盖成 null，否则套餐降级后还会一直沿用旧的付费档。
     */
    private suspend fun recordContext(
        account: OAuthAccountRecord,
        egress: String,
        project: String,
        tier: String?,
        tierReported: Boolean = tier != null,
        preparation: DiagnosticPreparationDeadline.Budget? = null,
    ): Context? {
        val previous = negotiated[account.id]?.takeIf { it.account.matchesCredentialSnapshot(account) }
        val merged = if (tierReported) tier else previous?.takeIf { it.egress == egress }?.tier
        val saved = databaseStep(preparation) {
            accounts.saveUpstreamContextIfSnapshotMatches(account, project, merged, now())
        }
        if (!saved) {
            negotiated[account.id]?.let { current ->
                if (current.account.matchesCredentialSnapshot(account)) negotiated.remove(account.id, current)
            }
            if (preparation != null) throw credentialReplaced()
            return null
        }
        negotiated[account.id] = Export(account, egress, project, merged)
        return Context(project, merged)
    }

    private class DatabaseValue<T>(val value: T)

    private suspend fun <T> databaseStep(
        preparation: DiagnosticPreparationDeadline.Budget?,
        operation: suspend () -> T,
    ): T {
        if (preparation == null) return operation()
        return (DiagnosticPreparationDeadline.withinSynchronous(preparation) { DatabaseValue(operation()) }
            ?: throw DiagnosticPreparationDeadline.deadlineExceeded()).value
    }

    /**
     * 上游调用失败只降级为 null，但协程取消必须原样抛出：吞掉取消会让被放弃的请求
     * 继续发起后续的上游调用。
     */
    private suspend fun <T> attempt(accountId: String, action: String, block: suspend () -> T): T? = try {
        block()
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Throwable) {
        logger.warn("{} failed, accountId={}, error={}", action, accountId, cause::class.simpleName)
        null
    }

    private fun normalizeProxy(proxyUrl: String?): String? = proxyUrl?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * 公开的目录探测：拿到账号的 project、tier 与允许拉取目录的 tier 列表。
     * 管理面刷新模型依赖这三项，不应自己访问 oauth_accounts 表拼装。
     */
    suspend fun probe(account: OAuthAccountRecord, accessToken: String, proxy: InetSocketAddress? = null): Probe {
        val loaded = loadCodeAssist(account.id, accessToken, proxy)
        val project = loaded.project ?: attempt(account.id, "onboardUser") {
            onboard(accessToken, loaded.tier ?: loaded.allowedTiers.firstOrNull(), proxy)
        }
        if (project == null) return Probe(null, loaded.tier, loaded.allowedTiers, loaded.paidTier)

        val context = recordContext(account, egressKey(proxy), project, loaded.catalogTier(), loaded.reportsTier())
            ?: throw credentialReplaced()
        return Probe(context.project, loaded.tier, loaded.allowedTiers, loaded.paidTier)
    }

    private suspend fun loadCodeAssist(accountId: String, accessToken: String, proxy: InetSocketAddress?): Loaded {
        val text = call(loadBaseUrl + "/v1internal:loadCodeAssist", accessToken, LOAD_BODY, proxy)
        val root = json.parseToJsonElement(text) as? JsonObject
            ?: throw IllegalStateException("loadCodeAssist 返回的不是 JSON 对象")
        val project = (root["cloudaicompanionProject"] as? JsonPrimitive)?.contentOrNull
            ?: (root["cloudaicompanionProject"] as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.contentOrNull }
        // currentTier 是当前实际开通的档位，paidTier 是账号有资格使用的付费档；
        // 两者必须分开保留：目录请求要用付费档，开通只能沿用当前档。
        val tier = (root["currentTier"] as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.contentOrNull }
        val paidTier = (root["paidTier"] as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.contentOrNull }
        // allowedTiers 是账号可以开通的 tier；currentTier 为空时要从中选一个开通。
        val allowed = (root["allowedTiers"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("id")?.let { id -> (id as? JsonPrimitive)?.contentOrNull } }
        if (project == null) {
            logger.info(
                "loadCodeAssist reports no project, accountId={}, tier={}, paidTier={}, allowedTiers={}",
                accountId,
                tier,
                paidTier,
                allowed,
            )
        }
        return Loaded(project, tier, allowed, paidTier)
    }

    private suspend fun onboard(accessToken: String, tier: String?, proxy: InetSocketAddress?): String? {
        // tier 来自上游响应，必须序列化而不是拼接，避免引号或反斜杠把请求体拼坏。
        val body = buildJsonObject {
            if (!tier.isNullOrBlank()) put("tier_id", tier)
            putJsonObject("metadata") {
                put("ide_type", "ANTIGRAVITY")
                put("ide_version", ideVersion)
                put("ide_name", "antigravity")
            }
        }.toString()
        repeat(ONBOARD_ATTEMPTS) {
            val text = call(baseUrl + "/v1internal:onboardUser", accessToken, body, proxy)
            val root = json.parseToJsonElement(text) as? JsonObject ?: return null
            if ((root["done"] as? JsonPrimitive)?.booleanOrNull == true) {
                val response = root["response"] as? JsonObject ?: return null
                return (response["cloudaicompanionProject"] as? JsonPrimitive)?.contentOrNull
                    ?: (response["cloudaicompanionProject"] as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.contentOrNull }
            }
            delay(ONBOARD_POLL_MS)
        }
        return null
    }

    private suspend fun call(
        url: String,
        accessToken: String,
        body: String,
        proxy: InetSocketAddress?,
    ): String {
        val response = clients.client(proxy).post(url) {
            header(HttpHeaders.Authorization, "Bearer " + accessToken)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header(HttpHeaders.UserAgent, userAgent())
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val text = response.bodyAsText()
        check(response.status.value in 200..299) { "Code Assist " + response.status.value + ": " + text }
        return text
    }

    private data class Loaded(
        val project: String?,
        val tier: String?,
        val allowedTiers: List<String> = emptyList(),
        val paidTier: String? = null,
    ) {
        /** 记录到上下文里的档位：优先付费档，因为它决定了目录与数据面能力。 */
        fun catalogTier(): String? = catalogTiers(tier, paidTier).firstOrNull()

        /** 上游是否报告了档位；免费档也会报告，用来清掉降级前的付费档。 */
        fun reportsTier(): Boolean = tier != null || paidTier != null
    }

    private companion object {
        const val TIER_TTL_MS = 3_600_000L
        const val ONBOARD_ATTEMPTS = 5
        const val ONBOARD_POLL_MS = 1_000L
        const val LOAD_BODY = "{\"metadata\":{\"ideType\":\"ANTIGRAVITY\"}}"
        /** 直连出口在缓存键里的占位值，ConcurrentHashMap 不接受 null。 */
        const val DIRECT = "\u0000direct"
        const val FREE_TIER = "free-tier"
        const val LEGACY_TIER = "legacy-tier"

        /**
         * fetchAvailableModels 只认付费档和当前档：免费档、legacy 档既不算付费能力，
         * 也不能显式命名（Google 会直接报错），所以只作为没有档位时的兜底。
         */
        fun catalogTiers(tier: String?, paidTier: String?): List<String> =
            listOfNotNull(paidTier, tier)
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != FREE_TIER && it != LEGACY_TIER }
                .distinct()
    }
}
