package dev.soven.llmgateway.proxy

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream

class ModelPriceReferenceServiceTest {

    private val httpClient = HttpClient(Java)

    @Test
    fun `matches common baseline models instantly without network`() = runBlocking {
        val service = ModelPriceReferenceService(httpClient)

        val gpt4o = service.lookup("gpt-4o")
        assertNotNull(gpt4o)
        assertEquals("gpt-4o", gpt4o?.matchedModel)
        assertEquals("USD", gpt4o?.currency)
        assertEquals(BigDecimal("2.50"), gpt4o?.inputPricePerMillion)
        assertEquals(BigDecimal("10.00"), gpt4o?.outputPricePerMillion)
        assertEquals(BigDecimal("1.25"), gpt4o?.cachedInputPricePerMillion)
        assertEquals("官方基准价", gpt4o?.source)

        val mini = service.lookup("openai/gpt-4o-mini")
        assertNotNull(mini)
        assertEquals(BigDecimal("0.15"), mini?.inputPricePerMillion)
        assertEquals(BigDecimal("0.60"), mini?.outputPricePerMillion)

        val deepseek = service.lookup("deepseek-chat")
        assertNotNull(deepseek)
        assertEquals(BigDecimal("0.27"), deepseek?.inputPricePerMillion)
        assertEquals(BigDecimal("1.10"), deepseek?.outputPricePerMillion)
        assertEquals(BigDecimal("0.07"), deepseek?.cachedInputPricePerMillion)

        val claude = service.lookup("claude-3-7-sonnet")
        assertNotNull(claude)
        assertEquals(BigDecimal("3.00"), claude?.inputPricePerMillion)
        assertEquals(BigDecimal("15.00"), claude?.outputPricePerMillion)
        assertEquals(BigDecimal("0.30"), claude?.cachedInputPricePerMillion)
    }

    @Test
    fun `strips date suffixes and provider namespaces`() = runBlocking {
        val service = ModelPriceReferenceService(httpClient)

        val dated = service.lookup("openai/gpt-4o-2024-11-20")
        assertNotNull(dated)
        assertEquals(BigDecimal("2.50"), dated?.inputPricePerMillion)

        val sonnetWithDot = service.lookup("anthropic/claude-3.5-sonnet")
        assertNotNull(sonnetWithDot)
        assertEquals(BigDecimal("3.00"), sonnetWithDot?.inputPricePerMillion)
    }

    @Test
    fun `returns null for completely unknown model when upstream unreachable`() = runBlocking {
        // Pointing to a dummy local port ensures it fails fast if it tries dynamic catalog
        val service = ModelPriceReferenceService(httpClient, catalogUrl = "http://127.0.0.1:1/catalog.json")
        val missing = service.lookup("non-existent-model-xyz-12345")
        assertNull(missing)
    }

    @Test
    fun `catalog lists models dev ids including models without prices and prefers exact matches`() = runBlocking {
        val body = """
            {
              "providers": {
                "openai": {
                  "name": "OpenAI",
                  "models": {
                    "gpt-4o": {"canonical_model_id": "gpt-4o", "cost": {"input": 2.5, "output": 10}},
                    "gpt-4o-2024-11-20": {"canonical_model_id": "gpt-4o-2024-11-20", "cost": {"input": 2.5, "output": 10}},
                    "free-model": {"canonical_model_id": "free-model"}
                  }
                },
                "deepseek": {
                  "name": "DeepSeek",
                  "models": {
                    "deepseek-v4.1-flash": {"canonical_model_id": "deepseek-v4.1-flash", "cost": {"input": 0.1, "output": 0.2}}
                  }
                },
                "alibaba": {
                  "name": "Alibaba",
                  "models": {}
                },
                "openrouter": {
                  "name": "OpenRouter",
                  "models": {
                    "Qwen/Qwen3.8-Flash": {"canonical_model_id": "alibaba/qwen3.8-flash"}
                  }
                }
              }
            }
        """.trimIndent()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/catalog.json") { exchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val service = ModelPriceReferenceService(
                httpClient,
                catalogUrl = "http://127.0.0.1:${server.address.port}/catalog.json",
            )
            val snapshot = service.catalogSnapshot()

            assertTrue(snapshot.loaded)
            assertNull(snapshot.error)
            assertEquals(
                setOf("gpt-4o", "gpt-4o-2024-11-20", "free-model", "deepseek-v4.1-flash", "Qwen/Qwen3.8-Flash"),
                snapshot.models.map { it.id }.toSet(),
            )
            assertEquals(listOf("gpt-4o"), service.findCatalogMatches("gpt-4o").map { it.id })
            assertEquals(listOf("free-model"), service.findCatalogMatches("free-model").map { it.id })
            assertEquals(
                listOf("deepseek-v4.1-flash"),
                service.findCatalogMatches("deepseek-v4.1-flash").map { it.id },
            )
            val qwen = service.findCatalogMatches("Qwen3.8-Flash").first()
            assertEquals("alibaba/qwen3.8-flash", qwen.canonicalId)
            assertEquals("Alibaba", qwen.developer)
            assertEquals("alibaba", qwen.developerId)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `does not retry a non-success catalog response during cooldown`() = runBlocking {
        val requests = AtomicInteger()
        val server = catalogServer(503, "temporarily unavailable", requests)
        try {
            val service = ModelPriceReferenceService(
                httpClient,
                catalogUrl = "http://127.0.0.1:${server.address.port}/catalog.json",
            )

            assertNull(service.lookup("unknown-model-one"))
            assertNull(service.lookup("unknown-model-two"))
            assertEquals("models.dev 返回 503", service.catalogSnapshot().error)
            assertEquals(1, requests.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `does not retry a catalog parsing exception during cooldown`() = runBlocking {
        val requests = AtomicInteger()
        val server = catalogServer(200, "not-json", requests)
        try {
            val service = ModelPriceReferenceService(
                httpClient,
                catalogUrl = "http://127.0.0.1:${server.address.port}/catalog.json",
            )

            val first = service.catalogSnapshot()
            val second = service.catalogSnapshot()

            assertFalse(first.loaded)
            assertFalse(second.loaded)
            assertEquals(first.error, second.error)
            assertEquals(1, requests.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `loads gzip encoded catalog responses`() = runBlocking {
        val body = """
            {"providers":{"openai":{"name":"OpenAI","models":{"gzip-model":{"canonical_model_id":"gzip-model"}}}}}
        """.trimIndent()
        val compressed = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(body.toByteArray()) }
            output.toByteArray()
        }
        val acceptedEncoding = AtomicReference<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/catalog.json") { exchange ->
            acceptedEncoding.set(exchange.requestHeaders.getFirst("Accept-Encoding"))
            exchange.responseHeaders.add("Content-Encoding", "gzip")
            exchange.sendResponseHeaders(200, compressed.size.toLong())
            exchange.responseBody.use { it.write(compressed) }
        }
        server.start()
        try {
            val service = ModelPriceReferenceService(
                httpClient,
                catalogUrl = "http://127.0.0.1:${server.address.port}/catalog.json",
            )

            val snapshot = service.catalogSnapshot()

            assertTrue(snapshot.loaded)
            assertNull(snapshot.error)
            assertEquals(listOf("gzip-model"), snapshot.models.map { it.id })
            assertEquals("gzip", acceptedEncoding.get())
        } finally {
            server.stop(0)
        }
    }

    private fun catalogServer(status: Int, body: String, requests: AtomicInteger): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/catalog.json") { exchange ->
            requests.incrementAndGet()
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }
}
