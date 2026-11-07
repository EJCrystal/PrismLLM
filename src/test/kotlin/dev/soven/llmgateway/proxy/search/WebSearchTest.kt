package dev.soven.llmgateway.proxy.search

import com.sun.net.httpserver.HttpServer
import dev.soven.llmgateway.config.WebSearchConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebSearchTest {
    @Test
    fun `falls back to the next backend when the first finds nothing`() = runBlocking {
        val service = WebSearchService(listOf(
            FakeBackend("empty") { SearchOutput("", emptyList()) },
            FakeBackend("working") {
                SearchOutput(
                    summary = "1. Go — https://go.dev/dl/\nGo 1.27.1",
                    hits = listOf(SearchHit("Go", "https://go.dev/dl/", "Go 1.27.1")),
                )
            },
        ))

        val result = service.search("latest go")

        assertEquals("https://go.dev/dl/", result.hits.single().url)
        assertTrue(result.summary.contains("Go 1.27.1"))
    }

    @Test
    fun `tavily response becomes a summary and hits`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/search") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            assertTrue(body.contains("latest go"))
            val response = """{"results":[{"title":"Go downloads","url":"https://go.dev/dl/","content":"Go 1.27.1 is the latest release."}]}"""
            val bytes = response.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val service = WebSearchService.fromConfig(
                WebSearchConfig(
                    enabled = true,
                    provider = "tavily",
                    apiKey = "tvly-test",
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                ),
                HttpClient(Java),
            )
            val result = service.search("latest go")
            assertEquals("Go downloads", result.hits.single().title)
            assertTrue(result.summary.startsWith("1. Go downloads — https://go.dev/dl/"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `fromConfig stays unavailable when required settings are missing`() {
        val client = HttpClient(Java)
        try {
            assertFalse(WebSearchService.fromConfig(WebSearchConfig(enabled = true, provider = "tavily"), client).available)
            assertFalse(WebSearchService.fromConfig(WebSearchConfig(enabled = true, provider = "searxng"), client).available)
            assertTrue(
                WebSearchService.fromConfig(
                    WebSearchConfig(enabled = true, provider = "tavily", apiKey = "tvly-test"),
                    client,
                ).available,
            )
            assertTrue(
                WebSearchService.fromConfig(
                    WebSearchConfig(enabled = true, provider = "searxng", baseUrl = "http://127.0.0.1:8080"),
                    client,
                ).available,
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `rejects search responses larger than the byte limit`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/search") { exchange ->
            val bytes = ByteArray(4 * 1024 * 1024 + 1) { 'x'.code.toByte() }
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val client = HttpClient(Java)
        try {
            val service = WebSearchService.fromConfig(
                WebSearchConfig(
                    enabled = true,
                    provider = "tavily",
                    apiKey = "tvly-test",
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                ),
                client,
            )
            val error = assertFailsWith<WebSearchException> { service.search("big") }
            assertTrue(error.message.orEmpty().contains("上限"), error.message)
        } finally {
            client.close()
            server.stop(0)
        }
    }
}

private class FakeBackend(
    override val name: String,
    private val answer: () -> SearchOutput,
) : WebSearchBackend {
    override suspend fun search(query: String, limit: Int): SearchOutput = answer()
}
