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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory

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
) {
    private val logger = LoggerFactory.getLogger(CodeAssistProjectResolver::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    data class Context(val project: String?, val tier: String?)

    suspend fun context(account: OAuthAccountRecord, accessToken: String): Context {
        val cached = accounts.upstreamContext(account.id)
        if (cached?.project != null && cached.checkedAt != null && now() - cached.checkedAt < TIER_TTL_MS) {
            return Context(cached.project, cached.tier)
        }
        val loaded = runCatching { loadCodeAssist(accessToken) }.getOrElse { cause ->
            logger.warn("loadCodeAssist failed, accountId={}, error={}", account.id, cause::class.simpleName)
            null
        } ?: return Context(cached?.project, cached?.tier)
        val project = loaded.project ?: runCatching { onboard(accessToken, loaded.tier) }.getOrElse { cause ->
            logger.warn("onboardUser failed, accountId={}, error={}", account.id, cause::class.simpleName)
            null
        }
        if (project != null) {
            accounts.saveUpstreamContext(account.id, project, loaded.tier, now())
        }
        return Context(project ?: cached?.project, loaded.tier ?: cached?.tier)
    }

    private suspend fun loadCodeAssist(accessToken: String): Loaded? {
        val text = call(loadBaseUrl + "/v1internal:loadCodeAssist", accessToken, LOAD_BODY)
        val root = json.parseToJsonElement(text) as? JsonObject ?: return null
        val project = (root["cloudaicompanionProject"] as? JsonPrimitive)?.contentOrNull
            ?: (root["cloudaicompanionProject"] as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.contentOrNull }
        val tier = (root["currentTier"] as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: (root["paidTier"] as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.contentOrNull }
        return Loaded(project, tier)
    }

    private suspend fun onboard(accessToken: String, tier: String?): String? {
        val body = buildString {
            append('{')
            if (!tier.isNullOrBlank()) {
                append("\"tier_id\":\"").append(tier).append("\",")
            }
            append("\"metadata\":{\"ide_type\":\"ANTIGRAVITY\",")
            append("\"ide_version\":\"").append(ideVersion).append("\",")
            append("\"ide_name\":\"antigravity\"}}")
        }
        repeat(ONBOARD_ATTEMPTS) {
            val text = call(baseUrl + "/v1internal:onboardUser", accessToken, body)
            val root = json.parseToJsonElement(text) as? JsonObject ?: return null
            if ((root["done"] as? JsonPrimitive)?.contentOrNull == "true") {
                val response = root["response"] as? JsonObject ?: return null
                return (response["cloudaicompanionProject"] as? JsonPrimitive)?.contentOrNull
                    ?: (response["cloudaicompanionProject"] as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.contentOrNull }
            }
            delay(ONBOARD_POLL_MS)
        }
        return null
    }

    private suspend fun call(url: String, accessToken: String, body: String): String {
        val response = httpClient.post(url) {
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

    private data class Loaded(val project: String?, val tier: String?)

    private companion object {
        const val TIER_TTL_MS = 3_600_000L
        const val ONBOARD_ATTEMPTS = 5
        const val ONBOARD_POLL_MS = 1_000L
        const val LOAD_BODY = "{\"metadata\":{\"ideType\":\"ANTIGRAVITY\"}}"
    }
}
