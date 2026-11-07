package dev.soven.llmgateway.proxy.search

import dev.soven.llmgateway.config.WebSearchConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
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
            val backend = when (config.provider.lowercase()) {
                "tavily" -> TavilyBackend(httpClient, config.apiKey.orEmpty(), config.baseUrl, config.maxPageChars)
                "brave" -> BraveBackend(httpClient, config.apiKey.orEmpty(), config.baseUrl, config.maxPageChars)
                "exa" -> ExaBackend(httpClient, config.apiKey.orEmpty(), config.baseUrl, config.maxPageChars)
                "deepseek" -> DeepSeekBackend(httpClient, config.apiKey.orEmpty(), config.baseUrl, config.maxPageChars)
                "searxng" -> SearxngBackend(httpClient, config.baseUrl.orEmpty(), config.maxPageChars)
                else -> return WebSearchService(emptyList(), config.timeoutMs, config.maxResults)
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

private suspend fun HttpResponse.readLimited(maxBytes: Int = 4 * 1024 * 1024): String {
    val body = bodyAsText()
    return if (body.length <= maxBytes) body else body.take(maxBytes)
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
        val response = client.post("$base/search") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", query)
                put("max_results", limit)
                put("search_depth", "basic")
            }.toString())
        }
        val json = Json.parseToJsonElement(ensureSuccess(response, name, apiKey)).jsonObject
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
        val response = client.get("$base/res/v1/web/search") {
            header("X-Subscription-Token", apiKey)
            parameter("q", query)
            parameter("count", limit)
        }
        val json = Json.parseToJsonElement(ensureSuccess(response, name, apiKey)).jsonObject
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
        val response = client.post("$base/search") {
            header("X-API-Key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", query)
                put("numResults", limit)
                put("contents", buildJsonObject {
                    put("text", buildJsonObject { put("maxCharacters", maxPageChars) })
                })
            }.toString())
        }
        val json = Json.parseToJsonElement(ensureSuccess(response, name, apiKey)).jsonObject
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
        val response = client.get("$base/search") {
            parameter("q", query)
            parameter("format", "json")
        }
        val json = Json.parseToJsonElement(ensureSuccess(response, name)).jsonObject
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
        val response = client.post("$base/v1/tools/search") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", query.take(128))
                put("count", limit)
            }.toString())
        }
        val json = Json.parseToJsonElement(ensureSuccess(response, name, apiKey)).jsonObject
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
