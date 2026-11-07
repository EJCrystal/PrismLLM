package dev.soven.llmgateway.proxy.search

import dev.soven.llmgateway.config.WEB_SEARCH_PROVIDERS
import dev.soven.llmgateway.config.WebSearchConfig
import dev.soven.llmgateway.config.webSearchBaseUrlError
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.cancel
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** 一条搜索结果。text 只用于喂给模型，不会原样发给客户端。 */
data class SearchHit(
    val title: String,
    val url: String,
    val text: String,
    val age: String? = null,
)

/** 搜索后端归一化后的结果。 */
data class SearchOutput(
    val summary: String,
    val hits: List<SearchHit>,
)

class WebSearchException(message: String, val status: Int? = null) : Exception(message)

/** 一个可替换的搜索 API。 */
interface WebSearchBackend {
    val name: String
    suspend fun search(query: String, limit: Int): SearchOutput
}

/**
 * 按顺序尝试搜索后端。与 magpie 一致：前一个失败或空结果时换下一个；
 * 所有后端都失败才抛错。
 */
class WebSearchService(
    private val backends: List<WebSearchBackend>,
    private val timeoutMs: Long = 15_000,
    private val maxResults: Int = 6,
) {
    val available: Boolean get() = backends.isNotEmpty()

    suspend fun search(query: String): SearchOutput {
        val trimmed = query.trim()
        require(trimmed.isNotEmpty()) { "搜索词不能为空" }
        var last: Throwable? = null
        for (backend in backends) {
            try {
                val output = withTimeout(timeoutMs) { backend.search(trimmed, maxResults) }
                if (output.hits.isNotEmpty()) return output
                last = WebSearchException("${backend.name} found nothing")
            } catch (e: TimeoutCancellationException) {
                // 单个后端超时是普通失败，继续尝试下一个；外层取消仍会在下面的分支重新抛出。
                last = e
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                last = e
            }
        }
        throw WebSearchException("web search failed: ${last?.message ?: "no search backend"}")
    }

    companion object {
        fun fromConfig(config: WebSearchConfig, httpClient: HttpClient): WebSearchService {
            if (!config.enabled) return WebSearchService(emptyList(), config.timeoutMs, config.maxResults)
            val provider = config.provider.lowercase()
            // 必需配置缺失时不要创建后端：环境变量可能在保存设置后被移除，此时应回到原有转换路径。
            if (provider !in WEB_SEARCH_PROVIDERS) {
                return WebSearchService(emptyList(), config.timeoutMs, config.maxResults)
            }
            if (provider == "searxng") {
                if (config.baseUrl.isNullOrBlank()) {
                    return WebSearchService(emptyList(), config.timeoutMs, config.maxResults)
                }
            } else if (config.apiKey.isNullOrBlank()) {
                return WebSearchService(emptyList(), config.timeoutMs, config.maxResults)
            }
            // 运行时再校验一次地址：设置可能来自数据库或启动后变化的环境变量，不能只依赖保存/启动校验。
            if (webSearchBaseUrlError(provider, config.baseUrl, requireHttps = provider != "searxng") != null) {
                return WebSearchService(emptyList(), config.timeoutMs, config.maxResults)
            }
            val backend = when (provider) {
                "tavily" -> TavilyBackend(httpClient, config.apiKey.orEmpty(), config.baseUrl, config.maxPageChars)
                "brave" -> BraveBackend(httpClient, config.apiKey.orEmpty(), config.baseUrl, config.maxPageChars)
                "exa" -> ExaBackend(httpClient, config.apiKey.orEmpty(), config.baseUrl, config.maxPageChars)
                "deepseek" -> DeepSeekBackend(httpClient, config.apiKey.orEmpty(), config.baseUrl, config.maxPageChars)
                else -> SearxngBackend(httpClient, config.baseUrl.orEmpty(), config.maxPageChars)
            }
            return WebSearchService(listOf(backend), config.timeoutMs, config.maxResults)
        }
    }
}

private data class SearchPage(
    val title: String,
    val url: String,
    val text: String,
    val age: String? = null,
)

private fun pagesOutput(pages: List<SearchPage>, maxChars: Int, limit: Int): SearchOutput {
    val cleaned = pages.filter { it.url.isNotBlank() }.map {
        it.copy(
            title = it.title.ifBlank { it.url },
            text = clip(plain(it.text), maxChars),
        )
    }.take(limit)
    val summary = cleaned.mapIndexed { index, page ->
        buildString {
            append(index + 1).append(". ").append(page.title).append(" — ").append(page.url)
            if (page.text.isNotBlank()) append('\n').append(page.text)
        }
    }.joinToString("\n\n")
    return SearchOutput(
        summary = summary,
        hits = cleaned.map { SearchHit(it.title, it.url, it.text, it.age) },
    )
}

private fun plain(value: String): String = value
    .replace(Regex("<[^>]*>"), " ")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace(Regex("\\s+"), " ")
    .trim()

private fun clip(value: String, maxChars: Int): String =
    if (value.length <= maxChars) value else value.take(maxChars).trimEnd() + "…"

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.string(key: String): String? = text(key)

/** 从响应通道按字节读取上限，避免先把超大响应完整读进内存。 */
private suspend fun HttpResponse.readLimited(maxBytes: Int = 4 * 1024 * 1024): String {
    val channel = bodyAsChannel()
    val bytes = channel.readRemaining(maxBytes.toLong() + 1L).readBytes()
    if (bytes.size > maxBytes) {
        channel.cancel()
        throw WebSearchException("搜索响应超过 ${maxBytes / 1024} KB 上限", status.value)
    }
    return bytes.toString(Charsets.UTF_8)
}

private suspend fun ensureSuccess(response: HttpResponse, name: String, secret: String? = null): String {
    val body = response.readLimited()
    if (!response.status.isSuccess()) {
        val safe = secret?.takeIf { it.isNotBlank() }?.let { body.replace(it, "***") } ?: body
        throw WebSearchException("$name ${response.status.value}: ${safe.take(500)}", response.status.value)
    }
    return body
}

private class TavilyBackend(
    private val client: HttpClient,
    private val apiKey: String,
    baseUrl: String?,
    private val maxPageChars: Int,
) : WebSearchBackend {
    override val name = "Tavily"
    private val base = (baseUrl ?: "https://api.tavily.com").trimEnd('/')

    override suspend fun search(query: String, limit: Int): SearchOutput {
        val body = client.preparePost("$base/search") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", query)
                put("max_results", limit)
                put("search_depth", "basic")
            }.toString())
        }.execute { response -> ensureSuccess(response, name, apiKey) }
        val json = Json.parseToJsonElement(body).jsonObject
        val results = (json["results"] as? JsonArray).orEmpty()
        val pages = results.mapNotNull { raw ->
            val item = raw as? JsonObject ?: return@mapNotNull null
            SearchPage(
                title = item.string("title").orEmpty(),
                url = item.string("url").orEmpty(),
                text = item.string("content").orEmpty(),
            )
        }
        return pagesOutput(pages, maxPageChars, limit)
    }
}

private class BraveBackend(
    private val client: HttpClient,
    private val apiKey: String,
    baseUrl: String?,
    private val maxPageChars: Int,
) : WebSearchBackend {
    override val name = "Brave Search"
    private val base = (baseUrl ?: "https://api.search.brave.com").trimEnd('/')

    override suspend fun search(query: String, limit: Int): SearchOutput {
        val body = client.prepareGet("$base/res/v1/web/search") {
            header("X-Subscription-Token", apiKey)
            parameter("q", query)
            parameter("count", limit)
        }.execute { response -> ensureSuccess(response, name, apiKey) }
        val json = Json.parseToJsonElement(body).jsonObject
        val results = ((json["web"] as? JsonObject)?.get("results") as? JsonArray).orEmpty()
        val pages = results.mapNotNull { raw ->
            val item = raw as? JsonObject ?: return@mapNotNull null
            SearchPage(
                title = item.string("title").orEmpty(),
                url = item.string("url").orEmpty(),
                text = item.string("description").orEmpty(),
            )
        }
        return pagesOutput(pages, maxPageChars, limit)
    }
}

private class ExaBackend(
    private val client: HttpClient,
    private val apiKey: String,
    baseUrl: String?,
    private val maxPageChars: Int,
) : WebSearchBackend {
    override val name = "Exa"
    private val base = (baseUrl ?: "https://api.exa.ai").trimEnd('/')

    override suspend fun search(query: String, limit: Int): SearchOutput {
        val body = client.preparePost("$base/search") {
            header("X-API-Key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", query)
                put("numResults", limit)
                put("contents", buildJsonObject {
                    put("text", buildJsonObject { put("maxCharacters", maxPageChars) })
                })
            }.toString())
        }.execute { response -> ensureSuccess(response, name, apiKey) }
        val json = Json.parseToJsonElement(body).jsonObject
        val results = (json["results"] as? JsonArray).orEmpty()
        val pages = results.mapNotNull { raw ->
            val item = raw as? JsonObject ?: return@mapNotNull null
            SearchPage(
                title = item.string("title").orEmpty(),
                url = item.string("url").orEmpty(),
                text = item.string("text").orEmpty(),
                age = item.string("publishedDate"),
            )
        }
        return pagesOutput(pages, maxPageChars, limit)
    }
}

private class SearxngBackend(
    private val client: HttpClient,
    baseUrl: String,
    private val maxPageChars: Int,
) : WebSearchBackend {
    override val name = "SearXNG"
    private val base = baseUrl.trimEnd('/')

    override suspend fun search(query: String, limit: Int): SearchOutput {
        val body = client.prepareGet("$base/search") {
            parameter("q", query)
            parameter("format", "json")
        }.execute { response -> ensureSuccess(response, name) }
        val json = Json.parseToJsonElement(body).jsonObject
        val results = (json["results"] as? JsonArray).orEmpty()
        val pages = results.mapNotNull { raw ->
            val item = raw as? JsonObject ?: return@mapNotNull null
            SearchPage(
                title = item.string("title").orEmpty(),
                url = item.string("url").orEmpty(),
                text = item.string("content").orEmpty(),
                age = item.string("publishedDate"),
            )
        }.take(limit)
        return pagesOutput(pages, maxPageChars, limit)
    }
}

private class DeepSeekBackend(
    private val client: HttpClient,
    private val apiKey: String,
    baseUrl: String?,
    private val maxPageChars: Int,
) : WebSearchBackend {
    override val name = "DeepSeek Search"
    private val base = (baseUrl ?: "https://api.deepseek.com").trimEnd('/')

    override suspend fun search(query: String, limit: Int): SearchOutput {
        val body = client.preparePost("$base/v1/tools/search") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", query.take(128))
                put("count", limit)
            }.toString())
        }.execute { response -> ensureSuccess(response, name, apiKey) }
        val json = Json.parseToJsonElement(body).jsonObject
        val results = (json["results"] as? JsonArray).orEmpty()
        val pages = results.mapNotNull { raw ->
            val item = raw as? JsonObject ?: return@mapNotNull null
            SearchPage(
                title = item.string("title").orEmpty(),
                url = item.string("url").orEmpty(),
                text = item.string("summary") ?: item.string("snippet").orEmpty(),
                age = item.string("date"),
            )
        }
        return pagesOutput(pages, maxPageChars, limit)
    }
}
