package dev.soven.llmgateway.proxy.codeassist

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Code Assist 的模型目录：fetchAvailableModels 按 tier 逐级尝试，被 400 的 tier
 * 记一天不再试；结果收成家族模型，供渠道模型列表和请求时的变体选择使用。
 * 对应 magpie 的 modelInfo / askTiers / tierRefused 与 antigravityFamilies。
 *
 * @author 文杰
 * @since 2026-11-06
 */
class CodeAssistModelCatalog(
    private val httpClient: HttpClient,
    private val baseUrl: String = "https://daily-cloudcode-pa.googleapis.com",
    private val now: () -> Long = System::currentTimeMillis,
    /** 账号代理：目录请求与 token 刷新走同一个出口。 */
    private val clients: CodeAssistClientPool = CodeAssistClientPool(httpClient),
) {
    private val logger = LoggerFactory.getLogger(CodeAssistModelCatalog::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val refused = ConcurrentHashMap<String, Long>()

    /** 按 project 缓存：不同账号对应不同的 cloud project，模型目录不能互相串用。 */
    private val snapshots = ConcurrentHashMap<String, Snapshot>()

    /**
     * 拉取并收成家族模型；全部 tier 都失败时返回空列表（不动已有缓存）。
     * 返回的目录与 project 绑定，请求时用 [Catalog.sentId] 选变体。
     */
    suspend fun fetchFor(
        accessToken: String,
        project: String,
        tiers: List<String>,
        proxy: InetSocketAddress? = null,
    ): Catalog {
        val raw = load(accessToken, project, tiers, proxy)
        if (raw.isNotEmpty()) snapshots[project] = Snapshot(raw)
        return Catalog(snapshots[project]?.models ?: raw)
    }

    /** 只要家族模型列表；全部 tier 都失败时返回空列表。 */
    suspend fun fetch(
        accessToken: String,
        project: String,
        tiers: List<String>,
        proxy: InetSocketAddress? = null,
    ): List<AntigravityModels.Model> = fetchFor(accessToken, project, tiers, proxy).models

    private suspend fun load(
        accessToken: String,
        project: String,
        tiers: List<String>,
        proxy: InetSocketAddress?,
    ): List<AntigravityModels.Model> {
        val candidates = (tiers.filter { it.isNotBlank() }.distinct() + listOf(""))
            .filter { tier -> tier.isEmpty() || !isRefused(project, tier) }
        for (tier in candidates) {
            val response = try {
                requestModels(accessToken, project, tier, proxy)
            } catch (cause: CancellationException) {
                // 取消必须原样抛出，不能当成失败继续尝试下一个 tier。
                throw cause
            } catch (cause: Throwable) {
                logger.warn("fetchAvailableModels failed, tier={}, error={}", tier, cause::class.simpleName)
                null
            }
            when {
                response == null -> continue
                response.status == 400 && tier.isNotEmpty() -> {
                    refused[tierKey(project, tier)] = now() + TIER_REFUSED_MS
                    logger.info("fetchAvailableModels refused tier {}, trying the next", tier)
                }
                response.status !in 200..299 -> {
                    logger.warn("fetchAvailableModels status {}", response.status)
                }
                else -> return parseModels(response.body)
            }
        }
        return emptyList()
    }

    /**
     * 一个 project 下的模型目录。请求时把家族模型落到当前等级的具体变体；
     * 还没有目录时原样返回模型 id。
     */
    class Catalog internal constructor(
        /** 上游原始模型 id 列表，用于家族与变体选择。 */
        val raw: List<AntigravityModels.Model>,
    ) {
        val models: List<AntigravityModels.Model> = AntigravityModels.collapse(raw)

        fun sentId(model: String, effort: String?): String =
            if (raw.isEmpty()) model else AntigravityModels.sentId(model, effort, raw)

        /**
         * 对外发布模型对应的可调用 id：家族的对外 id 不一定是真实变体，
         * 登记渠道时要用默认变体，目录缺失时请求也不会发出不存在的 id。
         */
        fun upstreamId(model: AntigravityModels.Model): String =
            if (raw.isEmpty()) model.id else AntigravityModels.defaultVariant(model.id, raw)
    }

    /** 已缓存的目录；没有缓存时返回 null，调用方决定是否补拉。 */
    fun cached(project: String): Catalog? = snapshots[project]?.let { Catalog(it.models) }

    private class Snapshot(val models: List<AntigravityModels.Model>)

    /** 请求时把家族模型落到当前等级的具体变体；没有缓存时原样返回。 */
    fun sentId(project: String?, model: String, effort: String?): String {
        val raw = project?.let { snapshots[it]?.models } ?: return model
        return AntigravityModels.sentId(model, effort, raw)
    }

    /** 仅供测试与诊断：某个 project 缓存的原始模型目录。 */
    fun snapshot(project: String? = null): List<AntigravityModels.Model> =
        (project?.let { snapshots[it] } ?: snapshots.values.maxByOrNull { it.models.size })?.models ?: emptyList()

    private suspend fun requestModels(
        accessToken: String,
        project: String,
        tier: String,
        proxy: InetSocketAddress?,
    ): HttpResult {
        val body = buildJsonObject {
            put("project", project)
            if (tier.isNotEmpty()) {
                putJsonObject("entitlement") { put("userTier", tier) }
            }
        }
        val response = clients.client(proxy).post(baseUrl + "/v1internal:fetchAvailableModels") {
            header(HttpHeaders.Authorization, "Bearer " + accessToken)
            header("X-Goog-Api-Client", "gl-node/22.21.1")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        return HttpResult(response.status.value, response.bodyAsText())
    }

    internal fun parseModels(text: String): List<AntigravityModels.Model> {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
        val models = root["models"] as? JsonObject ?: return emptyList()
        return models.entries.mapNotNull { (id, value) ->
            val model = value as? JsonObject ?: return@mapNotNull null
            if ((model["disabled"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
            if (id.startsWith("chat_") || id.startsWith("tab_")) return@mapNotNull null
            if (id in HIDDEN) return@mapNotNull null
            AntigravityModels.Model(
                id = id,
                name = (model["displayName"] as? JsonPrimitive)?.contentOrNull ?: id,
                context = (model["maxTokens"] as? JsonPrimitive)?.longOrNull,
                output = (model["maxOutputTokens"] as? JsonPrimitive)?.longOrNull,
                imageInput = (model["supportsImages"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }
    }

    private fun isRefused(project: String, tier: String): Boolean {
        val until = refused[tierKey(project, tier)] ?: return false
        if (until <= now()) {
            refused.remove(tierKey(project, tier))
            return false
        }
        return true
    }

    private fun tierKey(project: String, tier: String): String = project + "\u0000" + tier

    private data class HttpResult(val status: Int, val body: String)

    private companion object {
        const val TIER_REFUSED_MS = 24 * 60 * 60 * 1000L
        /** Antigravity 列出但不用于对话的模型。 */
        val HIDDEN = setOf(
            "chat_20706",
            "chat_23310",
            "tab_flash_lite_preview",
            "tab_jump_flash_lite_preview",
            "gemini-2.5-flash-thinking",
            "gemini-2.5-pro",
        )
    }
}
