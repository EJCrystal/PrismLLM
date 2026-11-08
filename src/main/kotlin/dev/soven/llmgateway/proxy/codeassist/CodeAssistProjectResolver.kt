package dev.soven.llmgateway.proxy.codeassist

import dev.soven.llmgateway.domain.OAuthAccountRecord
import dev.soven.llmgateway.repo.OAuthAccountRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.ContentType
import kotlinx.coroutines.delay
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
    private val ideVersion: String = "1.0.0",
    private val now: () -> Long = System::currentTimeMillis,
    /** 账号代理：project 发现与开通必须与 token 刷新走同一个出口。 */
    private val clients: CodeAssistClientPool = CodeAssistClientPool(httpClient),
) {
    private val logger = LoggerFactory.getLogger(CodeAssistProjectResolver::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    data class Context(val project: String?, val tier: String?)

    /** 模型目录探测结果：当前 tier 优先，其次账号可开通的 tier。 */
    data class Probe(val project: String?, val tier: String?, val allowedTiers: List<String> = emptyList()) {
        fun tiers(): List<String> = (listOfNotNull(tier).filter { it.isNotBlank() } + allowedTiers).distinct()
    }

    /** 上一次成功协商 project 时的出口；用于在代理变化时作废缓存。 */
    private val negotiatedProxy = ConcurrentHashMap<String, String>()

    private fun egressKey(proxyUrl: String?): String = normalizeProxy(proxyUrl) ?: DIRECT

    /**
     * @param proxy 账号出口代理；与刷新 access token 使用同一个，避免两套 IP。
     */
    suspend fun context(account: OAuthAccountRecord, accessToken: String, proxy: InetSocketAddress? = null): Context {
        val proxyKey = egressKey(account.proxyUrl)
        val cached = accounts.upstreamContext(account.id)
        // 缓存是按某个出口协商出来的：出口一变就必须重新发现，而不是等 TTL 过期。
        val sameEgress = proxyKey == (negotiatedProxy[account.id] ?: proxyKey)
        if (sameEgress && cached?.project != null && cached.checkedAt != null &&
            now() - cached.checkedAt < TIER_TTL_MS
        ) {
            return Context(cached.project, cached.tier)
        }
        val loaded = runCatching { loadCodeAssist(account.id, accessToken, proxy) }.getOrElse { cause ->
            logger.warn("loadCodeAssist failed, accountId={}, error={}", account.id, cause::class.simpleName)
            null
        } ?: return Context(cached?.project, cached?.tier)
        val project = loaded.project ?: runCatching {
            onboard(accessToken, loaded.tier ?: loaded.allowedTiers.firstOrNull(), proxy)
        }.getOrElse { cause ->
            logger.warn("onboardUser failed, accountId={}, error={}", account.id, cause::class.simpleName)
            null
        }
        if (project != null) {
            accounts.saveUpstreamContext(account.id, project, loaded.tier, now())
        }
        if (!sameEgress) {
            logger.info("account proxy changed, renegotiated code assist context, accountId={}", account.id)
        }
        negotiatedProxy[account.id] = proxyKey
        return Context(project ?: cached?.project, loaded.tier ?: cached?.tier)
    }

    private fun normalizeProxy(proxyUrl: String?): String? = proxyUrl?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * 公开的目录探测：拿到账号的 project、tier 与允许拉取目录的 tier 列表。
     * 管理面刷新模型依赖这三项，不应自己访问 oauth_accounts 表拼装。
     */
    suspend fun probe(account: OAuthAccountRecord, accessToken: String, proxy: InetSocketAddress? = null): Probe {
        val loaded = loadCodeAssist(account.id, accessToken, proxy)
        val project = loaded.project ?: runCatching {
            onboard(accessToken, loaded.tier ?: loaded.allowedTiers.firstOrNull(), proxy)
        }.getOrElse { cause ->
            logger.warn("onboardUser failed, accountId={}, error={}", account.id, cause::class.simpleName)
            null
        } ?: return Probe(accounts.upstreamContext(account.id)?.project, loaded.tier, loaded.allowedTiers)
        accounts.saveUpstreamContext(account.id, project, loaded.tier, now())
        return Probe(project, loaded.tier, loaded.allowedTiers)
    }

    private suspend fun loadCodeAssist(accountId: String, accessToken: String, proxy: InetSocketAddress?): Loaded {
        val text = call(loadBaseUrl + "/v1internal:loadCodeAssist", accessToken, LOAD_BODY, proxy)
        val root = json.parseToJsonElement(text) as? JsonObject
            ?: throw IllegalStateException("loadCodeAssist 返回的不是 JSON 对象")
        val project = (root["cloudaicompanionProject"] as? JsonPrimitive)?.contentOrNull
            ?: (root["cloudaicompanionProject"] as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.contentOrNull }
        val tier = (root["currentTier"] as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: (root["paidTier"] as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.contentOrNull }
        // allowedTiers 是账号可以开通的 tier；currentTier 为空时要从中选一个开通。
        val allowed = (root["allowedTiers"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("id")?.let { id -> (id as? JsonPrimitive)?.contentOrNull } }
        if (project == null) {
            logger.info("loadCodeAssist reports no project, accountId={}, tier={}, allowedTiers={}", accountId, tier, allowed)
        }
        return Loaded(project, tier, allowed)
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
            header("X-Goog-Api-Client", "gl-node/22.21.1")
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
    )

    private companion object {
        const val TIER_TTL_MS = 3_600_000L
        const val ONBOARD_ATTEMPTS = 5
        const val ONBOARD_POLL_MS = 1_000L
        const val LOAD_BODY = "{\"metadata\":{\"ideType\":\"ANTIGRAVITY\"}}"
        /** 直连出口在缓存键里的占位值，ConcurrentHashMap 不接受 null。 */
        const val DIRECT = "\u0000direct"
    }
}
