package dev.soven.llmgateway.proxy

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import dev.soven.llmgateway.api.ModelPriceReferenceResponse
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream

/**
 * 模型参考单价。所有价格单位为每百万 token（USD）。
 *
 * @author 文杰
 * @since 2026-10-22
 */
data class ReferencePrice(
    val model: String,
    val matchedModel: String,
    val currency: String = "USD",
    val inputPricePerMillion: BigDecimal,
    val outputPricePerMillion: BigDecimal,
    val cachedInputPricePerMillion: BigDecimal? = null,
    val provider: String? = null,
    val source: String,
) {
    fun toResponse(): ModelPriceReferenceResponse = ModelPriceReferenceResponse(
        model = model,
        matchedModel = matchedModel,
        currency = currency,
        inputPricePerMillion = inputPricePerMillion.stripTrailingZeros().toPlainString(),
        outputPricePerMillion = outputPricePerMillion.stripTrailingZeros().toPlainString(),
        cachedInputPricePerMillion = cachedInputPricePerMillion?.stripTrailingZeros()?.toPlainString(),
        provider = provider,
        source = source,
    )
}

/**
 * models.dev 目录中的一个模型。即使没有价格信息，也会保留 ID 供管理面比对。
 */
data class CatalogModel(
    val id: String,
    val canonicalId: String,
    val provider: String,
    val providerId: String,
    /**
     * 模型开发者，而不是提供模型服务的渠道。优先从 canonical_model_id 的命名空间解析。
     */
    val developer: String,
    val developerId: String,
)

/**
 * models.dev 目录的当前快照。loaded=false 时 error 描述加载失败原因。
 */
data class CatalogSnapshot(
    val loaded: Boolean,
    val error: String?,
    val models: List<CatalogModel>,
)

/**
 * 从内置核心官方基准库与开源平台（models.dev）查询模型参考单价。
 *
 * @author 文杰
 * @since 2026-10-22
 */
class ModelPriceReferenceService(
    private val httpClient: HttpClient,
    private val catalogUrl: String = "https://models.dev/catalog.json",
    private val cacheDuration: Duration = Duration.ofDays(1),
    private val preRefreshLead: Duration = Duration.ofHours(1),
    /**
     * 缓存到期后仍可继续使用最后一次成功快照的时长。仅用于短暂的上游故障，
     * 超期后目录和 models.dev 参考价都视为不可用。
     */
    private val staleFallbackDuration: Duration = Duration.ofDays(1),
    private val nanoTime: () -> Long = { System.nanoTime() },
) {
    init {
        require(!cacheDuration.isZero && !cacheDuration.isNegative) {
            "models.dev 缓存有效期必须大于 0"
        }
        require(!preRefreshLead.isNegative) {
            "models.dev 提前刷新时间不能为负数"
        }
        require(preRefreshLead < cacheDuration) {
            "models.dev 提前刷新时间必须小于缓存有效期"
        }
        require(!staleFallbackDuration.isNegative) {
            "models.dev 陈旧快照兜底时间不能为负数"
        }
    }

    private val logger = LoggerFactory.getLogger(ModelPriceReferenceService::class.java)

    private data class BaselineEntry(
        val provider: String,
        val input: BigDecimal,
        val output: BigDecimal,
        val cached: BigDecimal? = null,
    )

    private val baseline = mapOf(
        // OpenAI
        "gpt-4o" to BaselineEntry("OpenAI", BigDecimal("2.50"), BigDecimal("10.00"), BigDecimal("1.25")),
        "gpt-4o-mini" to BaselineEntry("OpenAI", BigDecimal("0.15"), BigDecimal("0.60"), BigDecimal("0.075")),
        "gpt-4o-2024-11-20" to BaselineEntry("OpenAI", BigDecimal("2.50"), BigDecimal("10.00"), BigDecimal("1.25")),
        "gpt-4o-2024-08-06" to BaselineEntry("OpenAI", BigDecimal("2.50"), BigDecimal("10.00"), BigDecimal("1.25")),
        "gpt-4o-mini-2024-07-18" to BaselineEntry("OpenAI", BigDecimal("0.15"), BigDecimal("0.60"), BigDecimal("0.075")),
        "gpt-4-turbo" to BaselineEntry("OpenAI", BigDecimal("10.00"), BigDecimal("30.00")),
        "gpt-4" to BaselineEntry("OpenAI", BigDecimal("30.00"), BigDecimal("60.00")),
        "gpt-3.5-turbo" to BaselineEntry("OpenAI", BigDecimal("0.50"), BigDecimal("1.50")),
        "o1" to BaselineEntry("OpenAI", BigDecimal("15.00"), BigDecimal("60.00"), BigDecimal("7.50")),
        "o1-preview" to BaselineEntry("OpenAI", BigDecimal("15.00"), BigDecimal("60.00"), BigDecimal("7.50")),
        "o1-mini" to BaselineEntry("OpenAI", BigDecimal("1.10"), BigDecimal("4.40"), BigDecimal("0.55")),
        "o3" to BaselineEntry("OpenAI", BigDecimal("2.00"), BigDecimal("8.00"), BigDecimal("0.50")),
        "o3-mini" to BaselineEntry("OpenAI", BigDecimal("1.10"), BigDecimal("4.40"), BigDecimal("0.55")),
        "o4-mini" to BaselineEntry("OpenAI", BigDecimal("1.10"), BigDecimal("4.40"), BigDecimal("0.275")),
        "chatgpt-4o-latest" to BaselineEntry("OpenAI", BigDecimal("5.00"), BigDecimal("20.00"), BigDecimal("2.50")),

        // DeepSeek
        "deepseek-chat" to BaselineEntry("DeepSeek", BigDecimal("0.27"), BigDecimal("1.10"), BigDecimal("0.07")),
        "deepseek-v3" to BaselineEntry("DeepSeek", BigDecimal("0.27"), BigDecimal("1.10"), BigDecimal("0.07")),
        "deepseek-reasoner" to BaselineEntry("DeepSeek", BigDecimal("0.55"), BigDecimal("2.19"), BigDecimal("0.14")),
        "deepseek-r1" to BaselineEntry("DeepSeek", BigDecimal("0.55"), BigDecimal("2.19"), BigDecimal("0.14")),

        // Anthropic
        "claude-3-7-sonnet" to BaselineEntry("Anthropic", BigDecimal("3.00"), BigDecimal("15.00"), BigDecimal("0.30")),
        "claude-3.7-sonnet" to BaselineEntry("Anthropic", BigDecimal("3.00"), BigDecimal("15.00"), BigDecimal("0.30")),
        "claude-3-5-sonnet" to BaselineEntry("Anthropic", BigDecimal("3.00"), BigDecimal("15.00"), BigDecimal("0.30")),
        "claude-3.5-sonnet" to BaselineEntry("Anthropic", BigDecimal("3.00"), BigDecimal("15.00"), BigDecimal("0.30")),
        "claude-3-5-haiku" to BaselineEntry("Anthropic", BigDecimal("0.80"), BigDecimal("4.00"), BigDecimal("0.08")),
        "claude-3.5-haiku" to BaselineEntry("Anthropic", BigDecimal("0.80"), BigDecimal("4.00"), BigDecimal("0.08")),
        "claude-3-opus" to BaselineEntry("Anthropic", BigDecimal("15.00"), BigDecimal("75.00"), BigDecimal("1.50")),
        "claude-3.0-opus" to BaselineEntry("Anthropic", BigDecimal("15.00"), BigDecimal("75.00"), BigDecimal("1.50")),
        "claude-3-haiku" to BaselineEntry("Anthropic", BigDecimal("0.25"), BigDecimal("1.25"), BigDecimal("0.03")),

        // Google
        "gemini-2.5-flash" to BaselineEntry("Google", BigDecimal("0.10"), BigDecimal("0.40"), BigDecimal("0.025")),
        "gemini-2.5-pro" to BaselineEntry("Google", BigDecimal("1.25"), BigDecimal("5.00"), BigDecimal("0.3125")),
        "gemini-2.0-flash" to BaselineEntry("Google", BigDecimal("0.10"), BigDecimal("0.40"), BigDecimal("0.025")),
        "gemini-1.5-flash" to BaselineEntry("Google", BigDecimal("0.075"), BigDecimal("0.30"), BigDecimal("0.01875")),
        "gemini-1.5-pro" to BaselineEntry("Google", BigDecimal("1.25"), BigDecimal("5.00"), BigDecimal("0.3125")),

        // Qwen
        "qwen-turbo" to BaselineEntry("Qwen", BigDecimal("0.05"), BigDecimal("0.20")),
        "qwen-plus" to BaselineEntry("Qwen", BigDecimal("0.11"), BigDecimal("0.28")),
        "qwen-max" to BaselineEntry("Qwen", BigDecimal("0.28"), BigDecimal("1.33")),
        "qwen-long" to BaselineEntry("Qwen", BigDecimal("0.07"), BigDecimal("0.28")),
        "qwen2.5-72b-instruct" to BaselineEntry("Qwen", BigDecimal("0.35"), BigDecimal("0.40")),
        "qwen2.5-32b-instruct" to BaselineEntry("Qwen", BigDecimal("0.20"), BigDecimal("0.20")),
        "qwen2.5-7b-instruct" to BaselineEntry("Qwen", BigDecimal("0.07"), BigDecimal("0.07")),

        // Zhipu AI
        "glm-4-plus" to BaselineEntry("Zhipu AI", BigDecimal("0.70"), BigDecimal("1.40")),
        "glm-4-air" to BaselineEntry("Zhipu AI", BigDecimal("0.14"), BigDecimal("0.14")),
        "glm-4-flash" to BaselineEntry("Zhipu AI", BigDecimal("0.00"), BigDecimal("0.00")),

        // Meta Llama
        "llama-3.3-70b-instruct" to BaselineEntry("Meta", BigDecimal("0.13"), BigDecimal("0.39")),
        "llama-3.3-70b" to BaselineEntry("Meta", BigDecimal("0.13"), BigDecimal("0.39")),
        "llama-3.1-405b-instruct" to BaselineEntry("Meta", BigDecimal("0.80"), BigDecimal("2.40")),
        "llama-3.1-70b-instruct" to BaselineEntry("Meta", BigDecimal("0.13"), BigDecimal("0.39")),
        "llama-3.1-8b-instruct" to BaselineEntry("Meta", BigDecimal("0.02"), BigDecimal("0.05")),
    )

    private data class CatalogState(
        val models: List<CatalogModel>,
        val index: Map<String, List<CatalogModel>>,
        val prices: Map<String, ReferencePrice>,
        val loadedAtNanos: Long,
    )

    private val catalogCache: Cache<String, CatalogState> = CacheBuilder.newBuilder()
        .expireAfterWrite(cacheDuration.toMillis().coerceAtLeast(1), TimeUnit.MILLISECONDS)
        .build()
    private val lastGoodCatalog = AtomicReference<CatalogState?>(null)
    private val loadMutex = Mutex()
    private val refreshInterval = (cacheDuration - preRefreshLead)
        .coerceAtLeast(Duration.ofMillis(1))

    @Volatile
    private var catalogError: String? = null
    @Volatile
    private var lastFailureAtMillis = 0L
    private var refreshJob: Job? = null

    /**
     * 启动后台目录刷新。首次调用立即预热；之后在缓存到期前刷新，失败时每分钟重试。
     */
    @Synchronized
    fun start(scope: CoroutineScope): Job = refreshJob ?: scope.launch {
        var needsInitialLoad = true
        while (isActive) {
            if (needsInitialLoad) {
                if (!loadDynamicCatalog()) {
                    delay(RETRY_COOLDOWN_MS)
                    continue
                }
                needsInitialLoad = false
            }

            delay(refreshInterval.toMillis())
            while (isActive && !loadDynamicCatalog(force = true)) {
                delay(RETRY_COOLDOWN_MS)
            }
        }
    }.also { refreshJob = it }

    suspend fun catalogSnapshot(): CatalogSnapshot {
        val state = currentCatalogState()
        return CatalogSnapshot(
            loaded = state != null,
            error = catalogError,
            models = state?.models.orEmpty(),
        )
    }

    suspend fun findCatalogMatches(model: String): List<CatalogModel> {
        val state = currentCatalogState() ?: return emptyList()
        val candidates = candidateKeys(model)
        val matches = candidates
            .flatMap { state.index[it].orEmpty() }
            .distinctBy { it.providerId to it.id }
        val exactKeys = candidates.take(2).toSet()
        val exactMatches = matches.filter {
            it.id.lowercase() in exactKeys || it.canonicalId.lowercase() in exactKeys
        }
        return (if (exactMatches.isNotEmpty()) exactMatches else matches)
            .sortedWith(compareBy({ it.provider }, { it.id }))
    }

    suspend fun lookup(model: String): ReferencePrice? {
        val candidates = candidateKeys(model)

        // 1. 本地内置核心基准
        for (key in candidates) {
            val entry = baseline[key]
            if (entry != null) {
                return ReferencePrice(
                    model = model,
                    matchedModel = key,
                    currency = "USD",
                    inputPricePerMillion = entry.input,
                    outputPricePerMillion = entry.output,
                    cachedInputPricePerMillion = entry.cached,
                    provider = entry.provider,
                    source = "官方基准价",
                )
            }
        }

        // 2. models.dev 缓存
        val state = currentCatalogState() ?: return null
        for (key in candidates) {
            val cached = state.prices[key]
            if (cached != null) {
                return cached.copy(model = model)
            }
        }

        return null
    }

    private suspend fun currentCatalogState(): CatalogState? {
        catalogCache.getIfPresent(CATALOG_CACHE_KEY)?.let { return it }
        loadDynamicCatalog()
        catalogCache.getIfPresent(CATALOG_CACHE_KEY)?.let { return it }
        val fallback = lastGoodCatalog.get() ?: return null
        val staleAge = Duration.ofNanos(nanoTime() - fallback.loadedAtNanos) - cacheDuration
        return fallback.takeIf { staleAge <= staleFallbackDuration }
    }

    private suspend fun loadDynamicCatalog(force: Boolean = false): Boolean {
        loadMutex.withLock {
            if (!force && catalogCache.getIfPresent(CATALOG_CACHE_KEY) != null) return true
            if (lastFailureAtMillis != 0L && System.currentTimeMillis() - lastFailureAtMillis < RETRY_COOLDOWN_MS) {
                return false
            }
            try {
                val response = httpClient.get(catalogUrl) {
                    header(HttpHeaders.UserAgent, "PrismLLM/0.1")
                    header(HttpHeaders.AcceptEncoding, "gzip")
                    timeout {
                        requestTimeoutMillis = CATALOG_REQUEST_TIMEOUT_MS
                        connectTimeoutMillis = CATALOG_CONNECT_TIMEOUT_MS
                    }
                }
                if (!response.status.isSuccess()) {
                    recordFailure("models.dev 返回 " + response.status.value)
                    return false
                }

                val parsed = parseCatalog(decodeCatalogBody(response))
                val state = parsed.copy(loadedAtNanos = nanoTime())
                catalogCache.put(CATALOG_CACHE_KEY, state)
                lastGoodCatalog.set(state)
                catalogError = null
                lastFailureAtMillis = 0L
                logger.info(
                    "已从 models.dev 加载 {} 个模型（{} 个价格键）",
                    state.models.size,
                    state.prices.size,
                )
                return true
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                recordFailure(cause.message ?: "未知错误", cause)
                return false
            }
        }
    }

    private fun recordFailure(message: String, cause: Exception? = null) {
        catalogError = message
        lastFailureAtMillis = System.currentTimeMillis()
        if (cause != null) {
            logger.warn("拉取 models.dev 参考价库失败: {}", cause.message)
        }
    }

    private fun parseCatalog(text: String): CatalogState {
        val json = Json.parseToJsonElement(text).jsonObject
        val providers = json["providers"]?.jsonObject ?: emptyMap()
        val providerNames = providers.mapValues { (providerId, providerElem) ->
            (providerElem as? JsonObject)
                ?.get("name")
                ?.jsonPrimitive
                ?.content
                ?: providerId
        }
        val catalog = LinkedHashMap<String, CatalogModel>()
        val index = HashMap<String, MutableList<CatalogModel>>()
        val prices = HashMap<String, ReferencePrice>()
        for ((providerId, providerElem) in providers) {
            val pObj = providerElem as? JsonObject ?: continue
            val pName = providerNames[providerId] ?: providerId
            val models = pObj["models"]?.jsonObject ?: continue
            for ((modelId, modelElem) in models) {
                val mObj = modelElem as? JsonObject ?: continue
                val cid = mObj["canonical_model_id"]?.jsonPrimitive?.content ?: modelId
                val developerId = cid.substringBefore('/')
                    .takeIf { cid.contains('/') }
                    ?: providerId
                val catalogModel = CatalogModel(
                    id = modelId,
                    canonicalId = cid,
                    provider = pName,
                    providerId = providerId,
                    developer = providerNames[developerId] ?: developerId,
                    developerId = developerId,
                )
                catalog["$providerId\u0000$modelId"] = catalogModel
                for (key in (candidateKeys(modelId) + candidateKeys(cid)).distinct()) {
                    index.getOrPut(key) { mutableListOf() }.add(catalogModel)
                }

                val cost = mObj["cost"] as? JsonObject ?: continue
                val input = cost["input"]?.jsonPrimitive?.doubleOrNull ?: continue
                val output = cost["output"]?.jsonPrimitive?.doubleOrNull ?: continue
                val cached = cost["cache_read"]?.jsonPrimitive?.doubleOrNull
                val reference = ReferencePrice(
                    model = modelId,
                    matchedModel = cid,
                    currency = "USD",
                    inputPricePerMillion = BigDecimal.valueOf(input),
                    outputPricePerMillion = BigDecimal.valueOf(output),
                    cachedInputPricePerMillion = cached?.let { BigDecimal.valueOf(it) },
                    provider = pName,
                    source = "models.dev ($pName)",
                )
                for (key in candidateKeys(modelId) + candidateKeys(cid)) {
                    prices.putIfAbsent(key, reference)
                }
            }
        }
        return CatalogState(
            models = catalog.values.sortedWith(compareBy({ it.provider }, { it.id })),
            index = index.mapValues { (_, entries) ->
                entries.distinctBy { it.providerId to it.id }
            },
            prices = prices,
            loadedAtNanos = 0L,
        )
    }

    private suspend fun decodeCatalogBody(response: HttpResponse): String {
        val bytes = response.bodyAsBytes()
        val gzipEncoded = response.headers[HttpHeaders.ContentEncoding]
            ?.split(',')
            ?.any { encoding -> encoding.trim().let { it.equals("gzip", true) || it.equals("x-gzip", true) } }
            ?: false
        val decoded = if (gzipEncoded) {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        } else {
            bytes
        }
        return decoded.toString(Charsets.UTF_8)
    }

    private fun candidateKeys(raw: String): List<String> {
        val trimmed = raw.trim()
        val lower = trimmed.lowercase()
        val stripped = lower
            .removePrefix("openai/")
            .removePrefix("anthropic/")
            .removePrefix("deepseek/")
            .removePrefix("google/")
            .removePrefix("meta-llama/")
            .removePrefix("meta/")
            .removePrefix("alibaba/")
            .removePrefix("zhipuai/")
            .removePrefix("qwen/")
        val dotNormalized = stripped.replace('-', '.')
        val dashNormalized = stripped.replace('.', '-')
        val stem = stripped
            .replace(Regex("-\\d{4}-?\\d{2}-?\\d{2}$"), "")
            .replace(Regex("-latest$"), "")

        return listOf(
            lower,
            stripped,
            dotNormalized,
            dashNormalized,
            stem,
            stem.replace('.', '-'),
            stem.replace('-', '.'),
        ).distinct().filter { it.isNotEmpty() }
    }

    private companion object {
        const val CATALOG_CACHE_KEY = "models.dev"
        const val RETRY_COOLDOWN_MS = 60_000L
        const val CATALOG_REQUEST_TIMEOUT_MS = 30_000L
        const val CATALOG_CONNECT_TIMEOUT_MS = 10_000L
    }
}
