package dev.soven.prismllm.proxy.codeassist

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
    /** 当前 Antigravity 客户端 UA；由全局请求头目录提供，目录不可用时退回内置值。 */
    private val userAgent: () -> String = { DEFAULT_ANTIGRAVITY_CODE_ASSIST_USER_AGENT },
) {
    private val logger = LoggerFactory.getLogger(CodeAssistModelCatalog::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val refused = ConcurrentHashMap<String, Long>()

    /**
     * 按 project 缓存：不同账号对应不同的 cloud project，模型目录不能互相串用。
     * 条目带拉取时间与生效 tier 并设有效期：套餐或上游目录变化后不会一直用旧映射。
     */
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
        val loaded = load(accessToken, project, tiers, proxy)
        if (loaded != null) {
            snapshots[project] = Snapshot(loaded.models, loaded.tier, now())
        }
        return Catalog(current(project)?.models ?: loaded?.models.orEmpty())
    }

    /** 只要家族模型列表；全部 tier 都失败时返回空列表。 */
    suspend fun fetch(
        accessToken: String,
        project: String,
        tiers: List<String>,
        proxy: InetSocketAddress? = null,
    ): List<AntigravityModels.Model> = fetchFor(accessToken, project, tiers, proxy).models

    /** 一次成功的目录拉取：命中它的 tier 与原始模型列表。 */
    private class Loaded(val tier: String, val models: List<AntigravityModels.Model>)

    private suspend fun load(
        accessToken: String,
        project: String,
        tiers: List<String>,
        proxy: InetSocketAddress?,
    ): Loaded? {
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
                else -> return Loaded(tier, parseModels(response.body))
            }
        }
        return null
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

    /**
     * 仍在有效期内的目录；过期或 tier 变化（套餐切换）时返回 null，由调用方补拉。
     * [tier] 为空表示不校验 tier。
     */
    fun cached(project: String, tier: String? = null): Catalog? =
        current(project, tier)?.let { Catalog(it.models) }

    /**
     * 取当前有效的快照；过期条目顺便清掉，避免长期占用内存。
     * tier 变了说明套餐切换，旧的家族/变体映射不再可信，必须重新拉取。
     */
    private fun current(project: String, tier: String? = null): Snapshot? {
        val snapshot = snapshots[project] ?: return null
        if (now() - snapshot.fetchedAt >= CATALOG_TTL_MS) {
            snapshots.remove(project, snapshot)
            logger.info("model catalog expired, project={}, refreshing", project)
            return null
        }
        // 无 entitlement 的兜底拉取（tier 为空）对任何套餐都成立，不因 tier 变化失效。
        if (tier != null && snapshot.tier.isNotEmpty() && snapshot.tier != tier) {
            logger.info("model catalog tier changed, project={}, {} -> {}", project, snapshot.tier, tier)
            return null
        }
        return snapshot
    }

    private class Snapshot(
        val models: List<AntigravityModels.Model>,
        val tier: String,
        val fetchedAt: Long,
    )

    /** 请求时把家族模型落到当前等级的具体变体；没有有效缓存时原样返回。 */
    fun sentId(project: String?, model: String, effort: String?): String {
        val raw = project?.let { current(it)?.models } ?: return model
        return AntigravityModels.sentId(model, effort, raw)
    }

    /** 仅供测试与诊断：某个 project 缓存且仍在有效期内的原始模型目录。 */
    fun snapshot(project: String? = null): List<AntigravityModels.Model> =
        (project?.let { current(it) } ?: snapshots.values.maxByOrNull { it.models.size })?.models ?: emptyList()

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
            header(HttpHeaders.UserAgent, userAgent())
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
        /** 目录有效期：上游调整模型或账号套餐变化后，最多 6 小时就换到新映射。 */
        const val CATALOG_TTL_MS = 6 * 60 * 60 * 1000L
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
