package dev.soven.prismllm.proxy

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.soven.prismllm.channel.domain.ChannelKind
import dev.soven.prismllm.channel.domain.ChannelModelRecord
import dev.soven.prismllm.channel.domain.ChannelRecord
import dev.soven.prismllm.protocol.extractReportedModel
import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.shared.domain.OAuthProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNotNull
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

@Tag("component")
class ModelConnectivityTestTest {
    @Test
    fun `builds chat body with prompt and no reasoning effort`() {
        val body = Json.parseToJsonElement(
            buildModelTestBody(
                protocol = GatewayProtocol.CHAT_COMPLETIONS,
                model = "gpt-test",
                prompt = "say ok",
                maxOutputTokens = 128,
            ),
        ).jsonObject

        assertEquals("gpt-test", body["model"]?.jsonPrimitive?.content)
        assertNull(body["reasoning_effort"])
        assertEquals("say ok", body["messages"]?.jsonArray?.first()?.jsonObject?.get("content")?.jsonPrimitive?.content)
    }

    @Test
    fun `builds responses body without reasoning effort`() {
        val body = Json.parseToJsonElement(
            buildModelTestBody(
                protocol = GatewayProtocol.RESPONSES,
                model = "gpt-test",
                prompt = "say ok",
                maxOutputTokens = 128,
            ),
        ).jsonObject

        assertNull(body["reasoning"])
        assertFalse(body.containsKey("store") && body["store"]?.jsonPrimitive?.content == "true")
        assertEquals(false, body["store"]?.jsonPrimitive?.boolean)
        val input = body["input"]?.jsonArray
        assertEquals(1, input?.size)
        val item = input?.first()?.jsonObject
        assertEquals("message", item?.get("type")?.jsonPrimitive?.content)
        assertEquals("user", item?.get("role")?.jsonPrimitive?.content)
        val content = item?.get("content")?.jsonArray?.first()?.jsonObject
        assertEquals("input_text", content?.get("type")?.jsonPrimitive?.content)
        assertEquals("say ok", content?.get("text")?.jsonPrimitive?.content)
    }

    @Test
    fun `messages body omits reasoning effort`() {
        val body = Json.parseToJsonElement(
            buildModelTestBody(
                protocol = GatewayProtocol.MESSAGES,
                model = "claude-test",
                prompt = "say ok",
                maxOutputTokens = 128,
            ),
        ).jsonObject

        assertNull(body["reasoning_effort"])
        assertNull(body["reasoning"])
    }


    @Test
    fun `responses stream test body aggregates sse deltas and completed event`() {
        val body = parseResponsesStreamText(
            listOf(
                "event: response.output_text.delta",
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"OK\"}",
                "",
                "event: response.completed",
                "data: {\"type\":\"response.completed\",\"response\":{\"model\":\"gpt-6-luna\"},\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}",
            ).joinToString("\n"),
        )
        assertNotNull(body)
        assertEquals("OK", collectText(body?.get("output_text")))
        assertEquals("gpt-6-luna", body?.get("model")?.jsonPrimitive?.content)
    }

    @Test
    fun `responses stream with completed-only event extracts output array and usage`() {
        val body = parseResponsesStreamText(
            listOf(
                "event: response.completed",
                "data: {\"type\":\"response.completed\",\"response\":{\"model\":\"gpt-5.6-terra\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"terra ok\"}]}],\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}}",
            ).joinToString("\n"),
        )
        assertNotNull(body)
        assertEquals("terra ok", collectText(body?.get("output_text")))
        val output = body?.get("output")?.jsonArray
        assertEquals(1, output?.size)
        assertEquals("message", output?.first()?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `responses stream terminated by response failed reports error`() {
        val body = parseResponsesStreamText(
            listOf(
                "event: response.output_text.delta",
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}",
                "event: response.failed",
                "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"server_error\",\"message\":\"boom\"}}}",
            ).joinToString("\n"),
        )
        assertNotNull(body)
        val error = body?.get("error")?.jsonObject
        assertEquals("boom", error?.get("message")?.jsonPrimitive?.content)
    }

    @Test
    fun `http 200 stream with error events reports failure with message`() = runBlocking {
        val sseBody = listOf(
            "event: response.output_text.delta",
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}",
            "event: error",
            "data: {\"type\":\"error\",\"code\":\"server_error\",\"message\":\"stream boom\"}",
        ).joinToString("\n")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/responses") { exchange ->
            val bytes = sseBody.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val channel = ChannelRecord(
                id = "provider",
                name = "provider",
                baseUrl = "http://127.0.0.1:${server.address.port}",
                authType = "bearer",
                protocols = listOf(GatewayProtocol.RESPONSES),
                enabled = true,
                createdAt = 0,
                updatedAt = 0,
                modelMappings = listOf(ChannelModelRecord("gpt-6-luna")),
                kind = ChannelKind.PROVIDER,
                provider = OAuthProvider.CHATGPT,
            )
            val result = testOAuthAccountModelConnectivity(
                channel = channel,
                accessToken = "access-token",
                project = null,
                model = "gpt-6-luna",
                protocol = GatewayProtocol.RESPONSES,
                prompt = "say ok",
                httpClient = HttpClient(Java),
            )
            assertFalse(result.success)
            assertEquals(200, result.statusCode)
            assertEquals("stream boom", result.errorMessage)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `truncated stream without termination event reports failure`() {
        val body = parseResponsesStreamText(
            listOf(
                "event: response.output_text.delta",
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}",
            ).joinToString("\n"),
        )
        assertNotNull(body)
        assertNotNull(body?.get("stream_truncated"))
        val error = body?.get("error")?.jsonObject
        assertEquals("上游流式响应未收到终止事件，连接可能中断", error?.get("message")?.jsonPrimitive?.content)
    }

    @Test
    fun `terminated stream does not carry truncation marker`() {
        val body = parseResponsesStreamText(
            listOf(
                "event: response.completed",
                "data: {\"type\":\"response.completed\",\"response\":{\"model\":\"m\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}",
            ).joinToString("\n"),
        )
        assertNotNull(body)
        assertNull(body?.get("stream_truncated"))
    }

    @Test
    fun `truncated 200 stream reports failure via caller`() = runBlocking {
        val sseBody = listOf(
            "event: response.output_text.delta",
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}",
        ).joinToString("\n")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/responses") { exchange ->
            val bytes = sseBody.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val channel = ChannelRecord(
                id = "provider",
                name = "provider",
                baseUrl = "http://127.0.0.1:${server.address.port}",
                authType = "bearer",
                protocols = listOf(GatewayProtocol.RESPONSES),
                enabled = true,
                createdAt = 0,
                updatedAt = 0,
                modelMappings = listOf(ChannelModelRecord("gpt-6-luna")),
                kind = ChannelKind.PROVIDER,
                provider = OAuthProvider.CHATGPT,
            )
            val result = testOAuthAccountModelConnectivity(
                channel = channel,
                accessToken = "access-token",
                project = null,
                model = "gpt-6-luna",
                protocol = GatewayProtocol.RESPONSES,
                prompt = "say ok",
                httpClient = HttpClient(Java),
            )
            assertFalse(result.success)
            assertEquals("上游流式响应未收到终止事件，连接可能中断", result.errorMessage)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `empty stream before any delta reports truncation failure`() = runBlocking {
        val sseBody = listOf(
            "event: response.created",
            "data: {\"type\":\"response.created\"}",
        ).joinToString("\n")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/responses") { exchange ->
            val bytes = sseBody.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val channel = ChannelRecord(
                id = "provider",
                name = "provider",
                baseUrl = "http://127.0.0.1:${server.address.port}",
                authType = "bearer",
                protocols = listOf(GatewayProtocol.RESPONSES),
                enabled = true,
                createdAt = 0,
                updatedAt = 0,
                modelMappings = listOf(ChannelModelRecord("gpt-6-luna")),
                kind = ChannelKind.PROVIDER,
                provider = OAuthProvider.CHATGPT,
            )
            val result = testOAuthAccountModelConnectivity(
                channel = channel,
                accessToken = "access-token",
                project = null,
                model = "gpt-6-luna",
                protocol = GatewayProtocol.RESPONSES,
                prompt = "say ok",
                httpClient = HttpClient(Java),
            )
            assertFalse(result.success)
            assertTrue(result.errorMessage!!.contains("未收到终止事件"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `empty response body reports truncation failure`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/responses") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, -1)
            exchange.responseBody.close()
        }
        server.start()
        try {
            val channel = ChannelRecord(
                id = "provider",
                name = "provider",
                baseUrl = "http://127.0.0.1:${server.address.port}",
                authType = "bearer",
                protocols = listOf(GatewayProtocol.RESPONSES),
                enabled = true,
                createdAt = 0,
                updatedAt = 0,
                modelMappings = listOf(ChannelModelRecord("gpt-6-luna")),
                kind = ChannelKind.PROVIDER,
                provider = OAuthProvider.CHATGPT,
            )
            val result = testOAuthAccountModelConnectivity(
                channel = channel,
                accessToken = "access-token",
                project = null,
                model = "gpt-6-luna",
                protocol = GatewayProtocol.RESPONSES,
                prompt = "say ok",
                httpClient = HttpClient(Java),
            )
            assertFalse(result.success)
            assertTrue(result.errorMessage!!.contains("未收到终止事件"))
        } finally {
            server.stop(0)
        }
    }


    @Test
    fun `response incomplete due to token limit reports readable error`() {
        val body = parseResponsesStreamText(
            listOf(
                "data: {\"type\":\"response.incomplete\",\"response\":{\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}",
            ).joinToString("\n"),
        )
        assertNotNull(body)
        assertNull(body?.get("stream_truncated"))
        val error = body?.get("error")?.jsonObject
        assertEquals("响应因达到输出上限被截断（max_output_tokens）", error?.get("message")?.jsonPrimitive?.content)
    }


    @Test
    fun `completed stream with error does not report truncation`() {
        val body = parseResponsesStreamText(
            listOf(
                "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"message\":\"boom\"}}}",
            ).joinToString("\n"),
        )
        assertNotNull(body)
        assertNull(body?.get("stream_truncated"))
        assertNotNull(body?.get("error"))
    }


    @Test
    fun `responses stream with standalone error event reports error`() {
        val body = parseResponsesStreamText(
            listOf(
                "event: error",
                "data: {\"type\":\"error\",\"code\":\"server_error\",\"message\":\"standalone boom\"}",
            ).joinToString("\n"),
        )
        assertNotNull(body)
        val error = body?.get("error")?.jsonObject
        assertEquals("standalone boom", error?.get("message")?.jsonPrimitive?.content)
    }

    @Test
    fun `responses stream completed text replaces incremental text instead of duplicating`() {
        val body = parseResponsesStreamText(
            listOf(
                "event: response.output_text.delta",
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"OK\"}",
                "event: response.completed",
                "data: {\"type\":\"response.completed\",\"response\":{\"model\":\"gpt-6-luna\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"OK\"}]}]}}",
            ).joinToString("\n"),
        )
        assertNotNull(body)
        assertEquals("OK", collectText(body?.get("output_text")))
    }

    @Test
    fun `codex model list skips entries whose slug id and name are all blank`() {
        val models = parseModelList(
            """{"models":[{"slug":"","id":"valid"},{"slug":"","id":"  ","name":"blank-name"},{"slug":"kept"}]}""",
        )
        assertEquals(listOf("blank-name", "kept", "valid"), models.map { it.id })
    }

    @Test
    fun `code assist account test wraps the request with project and unwraps the response`() = runBlocking {
        val body = AtomicReference<String>()
        val userAgent = AtomicReference<String>()
        val authorization = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1internal:generateContent") { exchange ->
            body.set(exchange.requestBody.readAllBytes().decodeToString())
            userAgent.set(exchange.requestHeaders.getFirst("User-Agent"))
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            reply(
                exchange,
                """{"response":{"candidates":[{"content":{"parts":[{"text":"model ok"}]}}],"modelVersion":"gemini-3-pro"}}""",
            )
        }
        server.start()
        try {
            val channel = ChannelRecord(
                id = "provider",
                name = "provider",
                baseUrl = "http://127.0.0.1:${server.address.port}",
                authType = "bearer",
                protocols = listOf(GatewayProtocol.GEMINI),
                enabled = true,
                createdAt = 0,
                updatedAt = 0,
                modelMappings = listOf(ChannelModelRecord("gemini-3-pro")),
                kind = ChannelKind.PROVIDER,
                provider = OAuthProvider.ANTIGRAVITY,
            )

            val result = testOAuthAccountModelConnectivity(
                channel = channel,
                accessToken = "access-token",
                project = "aicode-consumers",
                model = "gemini-3-pro",
                protocol = GatewayProtocol.GEMINI,
                prompt = "say ok",
                httpClient = HttpClient(Java),
                codeAssistUserAgent = "antigravity/hub/2.30.0 windows/amd64",
            )

            assertTrue(result.success)
            assertEquals("model ok", result.responseText)
            assertEquals("gemini-3-pro", result.upstreamReportedModel)
            assertTrue(body.get().contains("\"project\":\"aicode-consumers\""))
            assertTrue(body.get().contains("\"request\""))
            assertEquals("antigravity/hub/2.30.0 windows/amd64", userAgent.get())
            assertEquals("Bearer access-token", authorization.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `code assist account test refuses to send without a project`() = runBlocking {
        val channel = ChannelRecord(
            id = "provider",
            name = "provider",
            baseUrl = "https://example.test",
            authType = "bearer",
            protocols = listOf(GatewayProtocol.GEMINI),
            enabled = true,
            createdAt = 0,
            updatedAt = 0,
            kind = ChannelKind.PROVIDER,
            provider = OAuthProvider.ANTIGRAVITY,
        )

        val result = testOAuthAccountModelConnectivity(
            channel = channel,
            accessToken = "access-token",
            project = null,
            model = "gemini-3-pro",
            protocol = GatewayProtocol.GEMINI,
            prompt = "say ok",
            httpClient = HttpClient(Java),
        )

        assertFalse(result.success)
        assertNull(result.statusCode)
        assertTrue(result.errorMessage!!.contains("未获取到 Antigravity 项目"))
    }

    @Test
    fun `extracts text from each protocol response`() {
        val chat = Json.parseToJsonElement(
            """{"choices":[{"message":{"content":"chat ok"}}]}""",
        ).jsonObject
        val responses = Json.parseToJsonElement(
            """{"output":[{"content":[{"type":"output_text","text":"responses ok"}]}]}""",
        ).jsonObject
        val messages = Json.parseToJsonElement(
            """{"content":[{"type":"text","text":"messages ok"}]}""",
        ).jsonObject

        assertEquals("chat ok", extractModelTestResponseText(GatewayProtocol.CHAT_COMPLETIONS, chat))
        assertEquals("responses ok", extractModelTestResponseText(GatewayProtocol.RESPONSES, responses))
        assertEquals("messages ok", extractModelTestResponseText(GatewayProtocol.MESSAGES, messages))
    }

    @Test
    fun extracts_model_identifier_reported_in_upstream_response_metadata() {
        val responses = Json.parseToJsonElement("{\"model\":\" mapped-model \"}").jsonObject
        val gemini = Json.parseToJsonElement("{\"modelVersion\":\"gemini-test\"}").jsonObject
        val invalid = Json.parseToJsonElement("{\"model\":42}").jsonObject

        assertEquals("mapped-model", extractReportedModel(GatewayProtocol.RESPONSES, responses))
        assertEquals("gemini-test", extractReportedModel(GatewayProtocol.GEMINI, gemini))
        assertNull(extractReportedModel(GatewayProtocol.CHAT_COMPLETIONS, invalid))
        assertNull(extractReportedModel(GatewayProtocol.MESSAGES, null))
    }

    @Test
    fun extracts_model_from_protocol_specific_stream_metadata_shapes() {
        val responses = Json.parseToJsonElement("{\"response\":{\"model\":\"responses-model\"}}").jsonObject
        val messages = Json.parseToJsonElement("{\"type\":\"message_start\",\"message\":{\"model\":\"messages-model\"}}").jsonObject

        assertEquals("responses-model", extractReportedModel(GatewayProtocol.RESPONSES, responses))
        assertEquals("messages-model", extractReportedModel(GatewayProtocol.MESSAGES, messages))
    }

    private fun reply(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
