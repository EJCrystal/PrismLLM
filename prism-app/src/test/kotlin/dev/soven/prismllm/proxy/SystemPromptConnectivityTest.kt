package dev.soven.prismllm.proxy

import com.sun.net.httpserver.HttpServer
import dev.soven.prismllm.channel.domain.ChannelRecord
import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.shared.domain.OAuthProvider
import dev.soven.prismllm.shared.domain.UpstreamRequestOverrides
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("component")
class SystemPromptConnectivityTest {
    @Test
    fun oauthCodeAssistUsesExactConfiguredPromptAndRetainsLegacyCloakWhenNotConfigured() = runBlocking {
        val captured = AtomicReference<JsonObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            captured.set(Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject)
            val response = """{"response":{"candidates":[{"content":{"parts":[{"text":"OK"}]}}]}}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        val client = HttpClient(Java)
        try {
            val channel = ChannelRecord(
                id = "test-channel", name = "Antigravity", baseUrl = "http://127.0.0.1:" + server.address.port,
                authType = "bearer", protocols = listOf(GatewayProtocol.GEMINI), enabled = true,
                createdAt = 0, updatedAt = 0, provider = OAuthProvider.ANTIGRAVITY,
            )
            val prompt = "You are Codex, an agent based on GPT-5."
            val result = testOAuthAccountModelConnectivity(
                channel, "fake-token", "fake-project", "gemini-test", GatewayProtocol.GEMINI, "hello", client,
                systemPrompt = prompt,
            )
            assertTrue(result.success, result.errorMessage)
            val request = captured.get()["request"]!!.jsonObject
            assertEquals(prompt, request["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
            val original = UpstreamRequestOverrides(body = JsonObject(mapOf("systemInstruction" to request["systemInstruction"]!!)))
            testOAuthAccountModelConnectivity(
                channel, "fake-token", "fake-project", "gemini-test", GatewayProtocol.GEMINI, "hello", client,
                globalOverrides = original,
            )
            val legacy = captured.get()["request"]!!.jsonObject["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
            assertTrue(legacy.contains("Y\u200bou are Codex"))
        } finally {
            client.close()
            server.stop(0)
        }
    }
}
