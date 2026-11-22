package dev.soven.prismllm.integration

import dev.soven.prismllm.admin.domain.SystemPromptRule
import dev.soven.prismllm.admin.domain.SystemPromptSettingsRecord
import dev.soven.prismllm.channel.domain.ChannelModelRecord
import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.shared.domain.UpstreamProtocolOverrides
import dev.soven.prismllm.shared.domain.UpstreamRequestOverrides
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
@Tag("shard-1")
@Tag("channel")
@Tag("protocol")
class ExtraBodyIntegrationTest : GatewayIntegrationTestBase() {
    @Test
    fun `native and translated stream and whole requests share configured session headers and body`() = runBlocking {
        app.systemPromptSettingsRepository.save(SystemPromptSettingsRecord(
            enabled = true,
            rules = listOf(SystemPromptRule("extra-body-prompt", "请求补充组合验证", prompt = "dedicated system prompt")),
        ))
        val channel = createChannelWithBaseUrl(
            "extra-body", fake.baseUrl + "/ok/v1", protocols = listOf(GatewayProtocol.RESPONSES),
            modelMappings = listOf(ChannelModelRecord("m1")),
            requestOverrides = UpstreamRequestOverrides(protocols = mapOf("responses" to UpstreamProtocolOverrides(
                headers = mapOf("X-Configured-Session" to "${'$'}{sessionId}/${'$'}{threadId}/${'$'}{subagentId}"),
                extraBody = Json.parseToJsonElement("""{"prompt_cache_key":"${'$'}{sessionId}","!service_tier":"default"}""").jsonObject,
            ))),
        )
        val persisted = app.channelRepository.find(channel.id)!!
        assertEquals(channel.requestOverrides, persisted.requestOverrides)
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            for (protocol in listOf(GatewayProtocol.RESPONSES, GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.MESSAGES)) {
                for (stream in listOf(false, true)) {
                    for (ownKey in listOf(false, true)) {
                        val request = buildString {
                            append("""{"model":"m1","stream":$stream,"max_tokens":128,""")
                            if (ownKey) append(""""prompt_cache_key":"client-key",""")
                            if (protocol == GatewayProtocol.RESPONSES) append(""""input":"hi"}""")
                            else append(""""messages":[{"role":"user","content":"hi"}]}""")
                        }.let { if (protocol == GatewayProtocol.RESPONSES) it.replace("max_tokens", "max_output_tokens") else it }
                        val response = client.post("/v1" + protocol.endpointPath) {
                            header(HttpHeaders.Authorization, "Bearer $key")
                            header("session_id", "session-1")
                            header("thread-id", "thread-2")
                            header("x-claude-code-agent-id", "agent-3")
                            contentType(ContentType.Application.Json)
                            setBody(request)
                        }
                        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                        response.bodyAsText()
                        val sent = fake.requests.last()
                        val body = Json.parseToJsonElement(sent.body).jsonObject
                        assertEquals(if (ownKey) "client-key" else "session-1", body["prompt_cache_key"]?.jsonPrimitive?.content)
                        assertEquals(listOf("session-1/thread-2/agent-3"), sent.requestHeaders["x-configured-session"])
                        assertEquals("default", body["service_tier"]?.jsonPrimitive?.content)
                        assertEquals("dedicated system prompt", body["instructions"]?.jsonPrimitive?.content)
                        assertFalse(body.containsKey("!service_tier"))
                    }
                }
            }
        }
    }

    @Test
    fun `failover preserves caller session but not another channels extra fields`() = runBlocking {
        val headers = mapOf("X-Configured-Session" to "${'$'}{sessionId}")
        val bad = createChannelWithBaseUrl(
            "bad", fake.baseUrl + "/500/v1", modelMappings = mappingsOf("m1"),
            requestOverrides = UpstreamRequestOverrides(headers = headers,
                extraBody = Json.parseToJsonElement("""{"bad_field":true,"prompt_cache_key":"${'$'}{sessionId}"}""").jsonObject),
        )
        val good = createChannelWithBaseUrl(
            "good", fake.baseUrl + "/ok/v1", modelMappings = mappingsOf("m2"),
            requestOverrides = UpstreamRequestOverrides(headers = headers,
                extraBody = Json.parseToJsonElement("""{"good_field":true,"prompt_cache_key":"${'$'}{sessionId}"}""").jsonObject),
        )
        exposeAsModel("failover", bad to "m1", good to "m2")
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"failover","messages":[{"role":"user","content":"first turn"}]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            response.bodyAsText()
            assertEquals(2, fake.requests.size)
            val bodies = fake.requests.map { Json.parseToJsonElement(it.body).jsonObject }
            assertEquals(bodies[0]["prompt_cache_key"], bodies[1]["prompt_cache_key"])
            assertFalse(bodies[1].containsKey("bad_field"))
            assertEquals("true", bodies[1]["good_field"]?.jsonPrimitive?.content)
        }
    }
}
