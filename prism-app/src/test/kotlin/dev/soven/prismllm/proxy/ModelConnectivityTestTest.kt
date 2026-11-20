package dev.soven.prismllm.proxy

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.soven.prismllm.channel.domain.ChannelKeyRecord
import dev.soven.prismllm.channel.domain.key.ChannelKeyState
import dev.soven.prismllm.channel.domain.ChannelKind
import dev.soven.prismllm.channel.domain.ChannelModelRecord
import dev.soven.prismllm.channel.domain.ChannelRecord
import dev.soven.prismllm.protocol.extractReportedModel
import dev.soven.prismllm.proxy.fingerprint.parseFingerprintNumbers
import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.shared.domain.KeyCredentialResolver
import dev.soven.prismllm.shared.domain.UpstreamRequestOverrides
import dev.soven.prismllm.shared.domain.UpstreamProtocolOverrides
import dev.soven.prismllm.shared.domain.OAuthProvider
import dev.soven.prismllm.shared.domain.Usage
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.content.OutgoingContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
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
            val result = prepareOAuthAccountModelConnectivity(
                channel = channel,
                accessToken = "access-token",
                project = null,
                model = "gpt-6-luna",
                protocol = GatewayProtocol.RESPONSES,
                prompt = "say ok",
                httpClient = HttpClient(Java),
            )()
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
            val result = prepareOAuthAccountModelConnectivity(
                channel = channel,
                accessToken = "access-token",
                project = null,
                model = "gpt-6-luna",
                protocol = GatewayProtocol.RESPONSES,
                prompt = "say ok",
                httpClient = HttpClient(Java),
            )()
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
            val result = prepareOAuthAccountModelConnectivity(
                channel = channel,
                accessToken = "access-token",
                project = null,
                model = "gpt-6-luna",
                protocol = GatewayProtocol.RESPONSES,
                prompt = "say ok",
                httpClient = HttpClient(Java),
            )()
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
            val result = prepareOAuthAccountModelConnectivity(
                channel = channel,
                accessToken = "access-token",
                project = null,
                model = "gpt-6-luna",
                protocol = GatewayProtocol.RESPONSES,
                prompt = "say ok",
                httpClient = HttpClient(Java),
            )()
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

            val result = prepareOAuthAccountModelConnectivity(
                channel = channel,
                accessToken = "access-token",
                project = "aicode-consumers",
                model = "gemini-3-pro",
                protocol = GatewayProtocol.GEMINI,
                prompt = "say ok",
                httpClient = HttpClient(Java),
                codeAssistUserAgent = "antigravity/hub/2.30.0 windows/amd64",
            )()

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

        val result = prepareOAuthAccountModelConnectivity(
            channel = channel,
            accessToken = "access-token",
            project = null,
            model = "gemini-3-pro",
            protocol = GatewayProtocol.GEMINI,
            prompt = "say ok",
            httpClient = HttpClient(Java),
        )()

        assertFalse(result.success)
        assertNull(result.statusCode)
        assertTrue(result.errorMessage!!.contains("未获取到 Antigravity 项目"))
    }

    @Test
    fun channel_diagnostics_preserve_model_and_prompt_for_every_protocol_while_honoring_safe_settings() = runBlocking {
        for (protocol in GatewayProtocol.entries) {
            val captured = captureDiagnosticRequest(protocol)
            assertIsolatedDiagnosticRequest(captured, protocol, provider = null)
        }
    }

    @Test
    fun oauth_diagnostics_preserve_inputs_and_authorization_for_responses_and_antigravity_envelope() = runBlocking {
        val oauthCases = listOf(
            OAuthProvider.CHATGPT to GatewayProtocol.RESPONSES,
            OAuthProvider.ANTIGRAVITY to GatewayProtocol.GEMINI,
        )
        for ((provider, protocol) in oauthCases) {
            val captured = captureDiagnosticRequest(protocol, provider)
            assertIsolatedDiagnosticRequest(captured, protocol, provider)
        }
    }

    @Test
    fun excessive_override_token_limits_are_capped_on_actual_requests_for_every_entry_point() = runBlocking {
        for (protocol in GatewayProtocol.entries) {
            assertIsolatedDiagnosticRequest(captureDiagnosticRequest(protocol, tokenOverride = 4_096), protocol, null, 128)
        }
        for ((provider, protocol) in listOf(OAuthProvider.CHATGPT to GatewayProtocol.RESPONSES,
            OAuthProvider.ANTIGRAVITY to GatewayProtocol.GEMINI)) {
            assertIsolatedDiagnosticRequest(captureDiagnosticRequest(protocol, provider, 4_096), protocol, provider, 128)
        }
    }

    private fun assertIsolatedDiagnosticRequest(
        captured: CapturedDiagnosticRequest,
        protocol: GatewayProtocol,
        provider: OAuthProvider?,
        expectedTokenLimit: Int = if (protocol == GatewayProtocol.GEMINI) 3 else 128,
    ) {
        val payload = if (provider == OAuthProvider.ANTIGRAVITY) captured.body["request"]!!.jsonObject else captured.body
        val prompt = when (protocol) {
            GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.MESSAGES ->
                payload["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content
            GatewayProtocol.RESPONSES ->
                payload["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
            GatewayProtocol.GEMINI ->
                payload["contents"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        }
        assertEquals("diagnostic prompt", prompt)
        assertFalse(captured.body.toString().contains("overridden-input"), "Overrides must not replace the diagnostic prompt")
        assertFalse(captured.body.toString().contains("overridden-model"), "Overrides must not replace the selected model")
        assertFalse(captured.body.toString().contains("injected-tool"), "Tools must not enter diagnostic requests")
        assertFalse("metadata" in payload)
        assertFalse("tools" in payload)
        assertEquals("0.4", when (protocol) {
            GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES, GatewayProtocol.MESSAGES -> payload["temperature"]?.jsonPrimitive?.content
            GatewayProtocol.GEMINI -> payload["generationConfig"]?.jsonObject?.get("temperature")?.jsonPrimitive?.content
        })
        val tokenLimit = when (protocol) {
            GatewayProtocol.CHAT_COMPLETIONS -> (payload["max_completion_tokens"] ?: payload["max_tokens"])?.jsonPrimitive?.int
            GatewayProtocol.RESPONSES -> payload["max_output_tokens"]?.jsonPrimitive?.int
            GatewayProtocol.MESSAGES -> payload["max_tokens"]?.jsonPrimitive?.int
            GatewayProtocol.GEMINI -> payload["generationConfig"]?.jsonObject?.get("maxOutputTokens")?.jsonPrimitive?.int
        }
        assertEquals(expectedTokenLimit, tokenLimit,
            "Diagnostic limits must cap at 128 while preserving stricter decoding overrides")
        assertEquals("Bearer diagnostic-token", captured.authorization)
        assertEquals("kept-channel-header", captured.channelHeader)
        assertEquals("kept-protocol-header", captured.protocolHeader)
        assertEquals("kept-global-header", captured.globalHeader)
        if (provider == OAuthProvider.ANTIGRAVITY) {
            assertTrue(captured.userAgent.orEmpty().startsWith("antigravity/"))
        } else {
            assertEquals("diagnostic-global-UA", captured.userAgent)
        }
        when (provider) {
            OAuthProvider.ANTIGRAVITY -> assertEquals("diagnostic-model", captured.body["model"]?.jsonPrimitive?.content)
            OAuthProvider.CHATGPT -> assertEquals("diagnostic-model", captured.body["model"]?.jsonPrimitive?.content)
            null -> if (protocol != GatewayProtocol.GEMINI) {
                assertEquals("diagnostic-model", payload["model"]?.jsonPrimitive?.content)
            } else {
                assertTrue(captured.path.endsWith("/models/diagnostic-model:generateContent"))
            }
        }
    }

    private suspend fun captureDiagnosticRequest(
        protocol: GatewayProtocol,
        provider: OAuthProvider? = null,
        tokenOverride: Int? = null,
    ): CapturedDiagnosticRequest {
        val captured = AtomicReference<CapturedDiagnosticRequest>()
        val channelOverrides = UpstreamRequestOverrides(
            headers = mapOf("X-Diagnostic-Channel" to "kept-channel-header"),
            body = Json.parseToJsonElement("""{"metadata":{"source":"channel"},"tools":[{"type":"function","name":"injected-tool"}]}""").jsonObject,
            protocols = mapOf(
                protocol.value to UpstreamProtocolOverrides(
                    headers = mapOf("X-Diagnostic-Protocol" to "kept-protocol-header"),
                    body = hostileDiagnosticBody(protocol).let { body ->
                        if (tokenOverride == null) body
                        else if (protocol == GatewayProtocol.GEMINI) kotlinx.serialization.json.JsonObject(body +
                            ("generationConfig" to kotlinx.serialization.json.JsonObject(body["generationConfig"]!!.jsonObject +
                                ("maxOutputTokens" to JsonPrimitive(tokenOverride)))))
                        else kotlinx.serialization.json.JsonObject(body + ((when (protocol) {
                            GatewayProtocol.CHAT_COMPLETIONS -> "max_completion_tokens"
                            GatewayProtocol.RESPONSES -> "max_output_tokens"
                            else -> "max_tokens"
                        }) to JsonPrimitive(tokenOverride)))
                    },
                ),
            ),
        )
        val channel = ChannelRecord(
            id = "diagnostic-request-channel",
            name = "diagnostic-request-channel",
            baseUrl = "https://diagnostic.example.test/v1",
            authType = "bearer",
            protocols = listOf(protocol),
            enabled = true,
            createdAt = 0,
            updatedAt = 0,
            requestOverrides = channelOverrides,
            kind = if (provider == null) ChannelKind.CUSTOM else ChannelKind.PROVIDER,
            provider = provider,
        )
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val content = request.body as? OutgoingContent.ByteArrayContent
                        ?: error("Expected synthetic diagnostic JSON content")
                    captured.set(
                        CapturedDiagnosticRequest(
                            path = request.url.encodedPath,
                            body = Json.parseToJsonElement(content.bytes().decodeToString()).jsonObject,
                            authorization = request.headers[HttpHeaders.Authorization],
                            channelHeader = request.headers["X-Diagnostic-Channel"],
                            protocolHeader = request.headers["X-Diagnostic-Protocol"],
                            globalHeader = request.headers["X-Diagnostic-Global"],
                            userAgent = request.headers[HttpHeaders.UserAgent],
                        ),
                    )
                    respond("{}", headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                }
            }
        }
        client.use {
            val globalOverrides = UpstreamRequestOverrides(
                userAgent = "diagnostic-global-UA",
                headers = mapOf("X-Diagnostic-Global" to "kept-global-header"),
                body = Json.parseToJsonElement("""{"metadata":{"source":"global"},"tools":[{"type":"function","name":"injected-tool"}]}""").jsonObject,
            )
            if (provider == null) {
                val key = ChannelKeyRecord(
                    id = "diagnostic-request-key",
                    channelId = channel.id,
                    name = "synthetic-key",
                    credentialCiphertext = "synthetic-ciphertext",
                    credentialNonce = "synthetic-nonce",
                    credentialKeyVersion = 1,
                    state = ChannelKeyState.ACTIVE,
                    priority = 0,
                    createdAt = 0,
                    updatedAt = 0,
                )
                testChannelModelConnectivity(
                    channel = channel,
                    credentialRef = key,
                    model = "diagnostic-model",
                    protocol = protocol,
                    prompt = "diagnostic prompt",
                    credentials = KeyCredentialResolver { "diagnostic-token" },
                    httpClient = it,
                    globalOverrides = globalOverrides,
                )
            } else {
                prepareOAuthAccountModelConnectivity(
                    channel = channel,
                    accessToken = "diagnostic-token",
                    project = if (provider == OAuthProvider.ANTIGRAVITY) "synthetic-project" else null,
                    model = "diagnostic-model",
                    protocol = protocol,
                    prompt = "diagnostic prompt",
                    httpClient = it,
                    globalOverrides = globalOverrides,
                )()
            }
        }
        return requireNotNull(captured.get())
    }

    private fun hostileDiagnosticBody(protocol: GatewayProtocol) = Json.parseToJsonElement(
        when (protocol) {
            GatewayProtocol.CHAT_COMPLETIONS -> """{"model":"overridden-model","messages":[{"role":"user","content":"overridden-input"}],"tools":[{"type":"function","function":{"name":"injected-tool"}}],"temperature":0.4,"metadata":{"source":"protocol"}}"""
            GatewayProtocol.RESPONSES -> """{"model":"overridden-model","input":[{"role":"user","content":"overridden-input"}],"tools":[{"type":"function","name":"injected-tool"}],"temperature":0.4,"metadata":{"source":"protocol"}}"""
            GatewayProtocol.MESSAGES -> """{"model":"overridden-model","messages":[{"role":"user","content":"overridden-input"}],"tools":[{"name":"injected-tool"}],"temperature":0.4,"metadata":{"source":"protocol"}}"""
            GatewayProtocol.GEMINI -> """{"contents":[{"role":"user","parts":[{"text":"overridden-input"}]}],"tools":[{"functionDeclarations":[{"name":"injected-tool"}]}],"generationConfig":{"temperature":0.4,"maxOutputTokens":3},"metadata":{"source":"protocol"}}"""
        },
    ).jsonObject

    private data class CapturedDiagnosticRequest(
        val path: String,
        val body: kotlinx.serialization.json.JsonObject,
        val authorization: String?,
        val channelHeader: String?,
        val protocolHeader: String?,
        val globalHeader: String?,
        val userAgent: String?,
    )

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

    @Test
    fun both_entry_points_redact_success_text_and_reported_model_without_changing_usage() = runBlocking {
        val credential = "sk-synthetic-success-secret"
        val body = """{"choices":[{"message":{"content":"before $credential after $credential"}}],"model":"model-$credential","usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10,"prompt_tokens_details":{"cached_tokens":2},"completion_tokens_details":{"reasoning_tokens":1}}}"""

        for (oauth in listOf(false, true)) {
            val result = diagnosticTransport(credential, oauth = oauth, responseBody = body)

            assertSuccessfulDiagnostic(result, "before [REDACTED] after [REDACTED]", "model-[REDACTED]")
        }
    }

    @Test
    fun both_entry_points_preserve_numeric_usage_encoded_as_strings() = runBlocking {
        val credential = "7"
        val body = """{"choices":[{"message":{"content":"before $credential after"}}],"model":"public-model","usage":{"prompt_tokens":"7","completion_tokens":"3","total_tokens":"10"}}"""
        for (oauth in listOf(false, true)) {
            val result = diagnosticTransport(credential, oauth = oauth, responseBody = body)
            assertSuccessfulDiagnostic(
                result, "before [REDACTED] after", "public-model",
                usage = Usage(input = 7, output = 3, cached = null, reasoning = null, total = 10),
            )
        }
    }

    @Test
    fun both_entry_points_redact_credentials_from_json_error_keys_without_changing_structure() = runBlocking {
        val credential = "synthetic-error-key"
        val escaped = unicodeEscapedCredential(credential)
        val body = """{"outer":[{"$escaped":"before $escaped after","status":502}]}"""
        assertFalse(body.contains(credential))
        for (oauth in listOf(false, true)) {
            val result = diagnosticTransport(credential, oauth = oauth, responseBody = body, status = HttpStatusCode.BadGateway)
            assertFailedDiagnostic(result, 502, """{"outer":[{"[REDACTED]":"before [REDACTED] after","status":502}]}""")
        }
    }

    @Test
    fun both_entry_points_redact_json_escaped_success_fields() = runBlocking {
        val credential = "sk-synthetic-escaped-secret"
        val escaped = unicodeEscapedCredential(credential)
        val body = """{"choices":[{"message":{"content":[{"type":"text","text":"before $escaped after"}]}}],"model":"model-$escaped","usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10,"prompt_tokens_details":{"cached_tokens":2},"completion_tokens_details":{"reasoning_tokens":1}}}"""
        assertFalse(body.contains(credential), "The credential must appear only after JSON decoding")

        for (oauth in listOf(false, true)) {
            val result = diagnosticTransport(credential, oauth = oauth, responseBody = body)

            assertSuccessfulDiagnostic(result, "before [REDACTED] after", "model-[REDACTED]")
        }
    }

    @Test
    fun both_entry_points_redact_plaintext_http_errors_before_truncation() = runBlocking {
        val credential = "sk-synthetic-http-secret"
        for (oauth in listOf(false, true)) {
            for (offset in listOf(0, 493, 503)) {
                val prefix = "e".repeat(offset)
                val result = diagnosticTransport(
                    credential,
                    oauth = oauth,
                    responseBody = "$prefix$credential tail",
                    status = HttpStatusCode.BadGateway,
                    contentType = ContentType.Text.Plain,
                )

                assertFailedDiagnostic(result, 502, (prefix + "[REDACTED] tail").take(500))
            }
        }
    }

    @Test
    fun both_entry_points_redact_decoded_http_errors_before_truncation() = runBlocking {
        val credential = "sk-synthetic-decoded-error"
        val escaped = unicodeEscapedCredential(credential)
        for (oauth in listOf(false, true)) {
            for (offset in listOf(0, 493, 503)) {
                val prefix = "e".repeat(offset)
                val result = diagnosticTransport(
                    credential,
                    oauth = oauth,
                    responseBody = """{"error":{"message":"$prefix$escaped tail"}}""",
                    status = HttpStatusCode.Unauthorized,
                )

                assertFailedDiagnostic(result, 401, (prefix + "[REDACTED] tail").take(500))
            }
        }
    }

    @Test
    fun both_entry_points_redact_transport_exception_messages_before_truncation() = runBlocking {
        val credential = "sk-synthetic-exception-secret"
        for (oauth in listOf(false, true)) {
            for (offset in listOf(0, 193, 203)) {
                val prefix = "e".repeat(offset)
                val result = diagnosticTransport(
                    credential,
                    oauth = oauth,
                    failure = IllegalStateException("$prefix$credential tail"),
                )

                assertFailedDiagnostic(result, null, "无法连接上游：" + (prefix + "[REDACTED] tail").take(200))
            }
        }
    }

    @Test
    fun both_entry_points_redact_success_fields_before_their_independent_limits() = runBlocking {
        val credential = "sk-synthetic-boundary-secret"
        val escaped = unicodeEscapedCredential(credential)
        for (oauth in listOf(false, true)) {
            for ((textOffset, modelOffset) in listOf(0 to 0, 24 to 193, 34 to 203)) {
                val textPrefix = "t".repeat(textOffset)
                val modelPrefix = "m".repeat(modelOffset)
                val result = diagnosticTransport(
                    credential,
                    oauth = oauth,
                    responseBody = """{"choices":[{"message":{"content":"$textPrefix$credential tail"}}],"model":"$modelPrefix$escaped tail"}""",
                    maxResponseChars = 31,
                )

                assertSuccessfulDiagnostic(
                    result,
                    (textPrefix + "[REDACTED] tail").take(31),
                    (modelPrefix + "[REDACTED] tail").take(200),
                    usage = null,
                )
            }
        }
    }

    @Test
    fun both_entry_points_redact_credentials_assembled_from_text_chunks() = runBlocking {
        val credential = "sk-synthetic-chunk-secret"
        val first = credential.take(10)
        val last = credential.drop(10)
        val prefix = "e".repeat(493)
        for (oauth in listOf(false, true)) {
            val success = diagnosticTransport(
                credential,
                oauth = oauth,
                responseBody = """{"choices":[{"message":{"content":[{"text":"before $first"},{"text":"$last after"}]}}],"model":"public-model"}""",
            )
            val failure = diagnosticTransport(
                credential,
                oauth = oauth,
                responseBody = """{"error":{"message":[{"text":"$prefix$first"},{"text":"$last tail"}]}}""",
                status = HttpStatusCode.BadRequest,
            )

            assertSuccessfulDiagnostic(success, "before [REDACTED] after", "public-model", usage = null)
            assertFailedDiagnostic(failure, 400, (prefix + "[REDACTED] tail").take(500))
        }
    }

    @Test
    fun both_entry_points_redact_credentials_assembled_across_responses_deltas() = runBlocking {
        val credential = "sk-synthetic-delta-secret"
        val first = credential.take(10)
        val last = credential.drop(10)
        val escaped = unicodeEscapedCredential(credential)
        val prefix = "t".repeat(24)
        val body = listOf(
            """data: {"type":"response.output_text.delta","delta":"$prefix$first"}""",
            """data: {"type":"response.output_text.delta","delta":"$last tail"}""",
            """data: {"type":"response.completed","response":{"model":"model-$escaped","usage":{"input_tokens":7,"output_tokens":3,"total_tokens":10,"input_tokens_details":{"cached_tokens":2},"output_tokens_details":{"reasoning_tokens":1}}}}""",
        ).joinToString("\n")
        assertFalse(body.contains(credential), "The credential must be decoded or assembled from separate events")

        for (oauth in listOf(false, true)) {
            val result = diagnosticTransport(
                credential,
                protocol = GatewayProtocol.RESPONSES,
                oauth = oauth,
                responseBody = body,
                contentType = ContentType.Text.EventStream,
                maxResponseChars = 31,
            )

            assertSuccessfulDiagnostic(result, (prefix + "[REDACTED] tail").take(31), "model-[REDACTED]")
        }
    }

    @Test
    fun both_entry_points_redact_failed_stream_errors_with_http_200() = runBlocking {
        val credential = "sk-synthetic-stream-error"
        val escaped = unicodeEscapedCredential(credential)
        val body = listOf(
            """data: {"type":"response.output_text.delta","delta":"partial $credential"}""",
            """data: {"type":"response.failed","response":{"error":{"code":"server_error","message":"denied $escaped"}}}""",
        ).joinToString("\n")

        for (oauth in listOf(false, true)) {
            val result = diagnosticTransport(
                credential,
                protocol = GatewayProtocol.RESPONSES,
                oauth = oauth,
                responseBody = body,
                contentType = ContentType.Text.EventStream,
            )

            assertFailedDiagnostic(result, 200, "denied [REDACTED]", responseText = "partial [REDACTED]")
        }
    }

    @Test
    fun both_entry_points_prefer_the_redacted_responses_error_over_failed_status() = runBlocking {
        val credential = "sk-synthetic-responses-failure"
        val escaped = unicodeEscapedCredential(credential)
        val body = """{"status":"failed","error":{"code":"server_error","message":"denied $escaped"},"output_text":"partial $escaped"}"""

        for (oauth in listOf(false, true)) {
            val result = diagnosticTransport(
                credential,
                protocol = GatewayProtocol.RESPONSES,
                oauth = oauth,
                responseBody = body,
            )

            assertFailedDiagnostic(result, 200, "denied [REDACTED]", responseText = "partial [REDACTED]")
        }
    }

    @Test
    fun native_code_assist_entry_redacts_unwrapped_success_and_error_fields() = runBlocking {
        val credential = "oauth-synthetic-code-assist-token"
        val escaped = unicodeEscapedCredential(credential)
        val success = diagnosticTransport(
            credential,
            protocol = GatewayProtocol.GEMINI,
            oauth = true,
            provider = OAuthProvider.ANTIGRAVITY,
            responseBody = """{"response":{"candidates":[{"content":{"parts":[{"text":"before $escaped after"}]}}],"modelVersion":"gemini-$escaped","usageMetadata":{"promptTokenCount":7,"candidatesTokenCount":2,"thoughtsTokenCount":1,"cachedContentTokenCount":2,"totalTokenCount":10}}}""",
        )
        val failure = diagnosticTransport(
            credential,
            protocol = GatewayProtocol.GEMINI,
            oauth = true,
            provider = OAuthProvider.ANTIGRAVITY,
            responseBody = """{"response":null,"error":{"message":"denied $escaped"}}""",
            status = HttpStatusCode.Forbidden,
        )

        assertSuccessfulDiagnostic(success, "before [REDACTED] after", "gemini-[REDACTED]")
        assertFailedDiagnostic(failure, 403, "denied [REDACTED]")
    }

    @Test
    fun both_entry_points_preserve_nonsecret_diagnostics_and_empty_credentials() = runBlocking {
        for (credential in listOf("sk-synthetic-unused-secret", "")) {
            for (oauth in listOf(false, true)) {
                val success = diagnosticTransport(
                    credential,
                    oauth = oauth,
                    responseBody = """{"choices":[{"message":{"content":"ordinary response"}}],"model":"public-model","usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10,"prompt_tokens_details":{"cached_tokens":2},"completion_tokens_details":{"reasoning_tokens":1}}}""",
                )
                val plainError = diagnosticTransport(
                    credential,
                    oauth = oauth,
                    responseBody = "  ordinary error: other-token  ",
                    status = HttpStatusCode.BadGateway,
                    contentType = ContentType.Text.Plain,
                )
                val jsonError = diagnosticTransport(
                    credential,
                    oauth = oauth,
                    responseBody = """{"error":{"message":"ordinary error: other-token"}}""",
                    status = HttpStatusCode.Forbidden,
                )
                val exception = diagnosticTransport(
                    credential,
                    oauth = oauth,
                    failure = IllegalStateException("ordinary connection failure: other-token"),
                )

                assertSuccessfulDiagnostic(success, "ordinary response", "public-model")
                assertFailedDiagnostic(plainError, 502, "ordinary error: other-token")
                assertFailedDiagnostic(jsonError, 403, "ordinary error: other-token")
                assertFailedDiagnostic(exception, null, "无法连接上游：ordinary connection failure: other-token")
            }
        }
    }

    @Test
    fun both_entry_points_replace_only_the_exact_credential() = runBlocking {
        val credential = "sk.test+[x](\$1)"
        for (oauth in listOf(false, true)) {
            val result = diagnosticTransport(
                credential,
                oauth = oauth,
                responseBody = """{"choices":[{"message":{"content":"keep sk.test+ and other-token; remove $credential"}}],"model":"public-model"}""",
            )

            assertSuccessfulDiagnostic(result, "keep sk.test+ and other-token; remove [REDACTED]", "public-model", usage = null)
        }
    }

    @Test
    fun both_entry_points_redact_credentials_without_rewriting_json_structure() = runBlocking {
        for (credential in listOf("model", "7", "sk-synthetic-quote\"-slash\\-token")) {
            val text = JsonPrimitive("before $credential after")
            val reportedModel = JsonPrimitive("reported-$credential")
            for (oauth in listOf(false, true)) {
                val success = diagnosticTransport(
                    credential,
                    oauth = oauth,
                    responseBody = """{"choices":[{"message":{"content":$text}}],"model":$reportedModel,"usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10,"prompt_tokens_details":{"cached_tokens":2},"completion_tokens_details":{"reasoning_tokens":1}}}""",
                )
                val fallback = diagnosticTransport(
                    credential,
                    oauth = oauth,
                    responseBody = """{"detail":$text}""",
                    status = HttpStatusCode.BadGateway,
                )

                assertSuccessfulDiagnostic(success, "before [REDACTED] after", "reported-[REDACTED]")
                assertFailedDiagnostic(fallback, 502, """{"detail":"before [REDACTED] after"}""")
            }
        }
    }

    @Test
    fun fingerprint_execution_does_not_return_raw_samples_for_transport_failure_or_timeout() = runBlocking {
        val credential = "fingerprint-transport-secret"
        val body = """{"choices":[{"message":{"content":"$credential should not escape"}}]}"""
        val transportFailure = fingerprintDiagnosticTransport(
            credential,
            body,
            maxResponseChars = 100,
            failure = IllegalStateException("upstream rejected $credential"),
        )
        val entered = CompletableDeferred<Unit>()
        val timedRequest = async {
            fingerprintDiagnosticTransport(
                credential,
                body,
                maxResponseChars = 100,
                timeoutMs = 2_000,
                responseDelayMs = 10_000,
                requestEntered = entered,
            )
        }
        withTimeout(5_000) { entered.await() }
        val timeout = withTimeout(5_000) { timedRequest.await() }

        assertFalse(transportFailure.result.success)
        assertNull(transportFailure.rawSample)
        assertFalse(transportFailure.result.errorMessage.orEmpty().contains(credential))
        assertFalse(timeout.result.success)
        assertNull(timeout.rawSample)
        assertNull(timeout.result.responseText)
        assertEquals("测试超时（2 秒）", timeout.result.errorMessage)
        assertFalse(timeout.result.errorMessage.orEmpty().contains(credential))
    }

    @Test
    fun truncated_chat_completions_keeps_safe_preview_but_never_supplies_numeric_fingerprint_sample() = runBlocking {
        val credential = "fingerprint-synthetic-secret"
        val numbers = (1..100).joinToString(",")
        val originalText = "$numbers $credential"
        assertEquals(100, parseFingerprintNumbers(originalText).size)

        for (reason in listOf("length", "content_filter", "stop")) {
            val execution = fingerprintDiagnosticTransport(
                credential,
                """{"choices":[{"message":{"content":${JsonPrimitive(originalText)}},"finish_reason":"$reason"}],"model":"public-model"}""",
                maxResponseChars = MAX_RESPONSE_CHARS,
            )

            assertSuccessfulDiagnostic(execution.result, "$numbers [REDACTED]", "public-model", usage = null)
            assertEquals(if (reason == "stop") originalText else null, execution.rawSample, reason)
        }
    }

    @Test
    fun candy_keeps_successful_original_answer_even_when_fingerprint_completion_is_rejected() = runBlocking {
        val original = """{"answer":21,"reason":"ok"}"""
        val execution = fingerprintDiagnosticTransport(
            "21",
            """{"choices":[{"message":{"content":${JsonPrimitive(original)}},"finish_reason":"length"}]}""",
            maxResponseChars = 100,
            requireCompleteSample = false,
        )
        assertEquals(original, execution.rawSample)
        assertTrue(execution.result.responseText.orEmpty().contains("[REDACTED]"))
        assertEquals(IqVerdict.PASSED, evaluateIqTest(IqBenchmark.CANDY, execution.result.success, execution.rawSample).verdict)
    }

    @Test
    fun gemini_samples_only_stop_or_compatible_missing_finish_reason() = runBlocking {
        for (reason in listOf("LANGUAGE", "OTHER", "STOP", "")) {
            val finish = if (reason.isEmpty()) "" else ",\"finishReason\":\"$reason\""
            val execution = fingerprintDiagnosticTransport(
                "gemini-secret",
                """{"candidates":[{"content":{"parts":[{"text":"sample gemini-secret"}]}$finish}]}""",
                maxResponseChars = 100,
                protocol = GatewayProtocol.GEMINI,
            )
            assertTrue(execution.result.success)
            assertEquals("sample [REDACTED]", execution.result.responseText)
            assertEquals(if (reason == "STOP" || reason.isEmpty()) "sample gemini-secret" else null, execution.rawSample)
        }
    }

    @Test
    fun other_protocols_with_explicit_truncation_keep_preview_but_do_not_supply_raw_samples() = runBlocking {
        val credential = "fingerprint-synthetic-secret"
        val cases = listOf(
            GatewayProtocol.MESSAGES to """{"content":[{"type":"text","text":"partial $credential"}],"stop_reason":"max_tokens","model":"public-model"}""",
            GatewayProtocol.MESSAGES to """{"content":[{"type":"text","text":"partial $credential"}],"stop_reason":"model_context_window_exceeded","model":"public-model"}""",
            GatewayProtocol.GEMINI to """{"candidates":[{"content":{"parts":[{"text":"partial $credential"}]},"finishReason":"MAX_TOKENS"}],"modelVersion":"public-model"}""",
        )
        for ((protocol, body) in cases) {
            val execution = fingerprintDiagnosticTransport(credential, body, maxResponseChars = 100, protocol = protocol)

            assertSuccessfulDiagnostic(execution.result, "partial [REDACTED]", "public-model", usage = null)
            assertNull(execution.rawSample, body)
        }
    }

    @Test
    fun incomplete_responses_json_does_not_supply_a_raw_fingerprint_sample() = runBlocking {
        for ((state, error) in listOf("incomplete" to "null", "failed" to "{\"message\":\" \"}", "in_progress" to "null")) {
            val execution = fingerprintDiagnosticTransport(
                "synthetic-json-secret",
                """{"status":"$state","error":$error,"output":[{"type":"message","content":[{"type":"output_text","text":"partial"}]}]}""",
                maxResponseChars = 100,
                protocol = GatewayProtocol.RESPONSES,
            )
            assertFailedDiagnostic(execution.result, 200, "上游 Responses 尚未成功完成：$state", responseText = "partial")
            assertNull(execution.rawSample, state)
        }
        for (state in listOf("\"status\":\"completed\",", "")) {
            val completed = fingerprintDiagnosticTransport(
                "synthetic-json-secret",
                """{${state}"output":[{"type":"message","content":[{"type":"output_text","text":"complete"}]}]}""",
                maxResponseChars = 100,
                protocol = GatewayProtocol.RESPONSES,
            )
            assertTrue(completed.result.success)
            assertEquals("complete", completed.rawSample)
        }
    }

    @Test
    fun responses_incomplete_details_disqualify_fingerprint_samples_despite_completed_or_missing_status() = runBlocking {
        val credential = "fingerprint-incomplete-details-secret"
        for (state in listOf("\"status\":\"completed\",", "")) {
            for (details in listOf("{\"reason\":\"max_output_tokens\"}", "{}")) {
                val response = """{${state}"incomplete_details":$details,"output_text":"partial $credential","model":"public-model"}"""
                val stream = "data: {\"type\":\"response.completed\",\"response\":$response}"
                for ((body, contentType) in listOf(response to ContentType.Application.Json, stream to ContentType.Text.EventStream)) {
                    val execution = fingerprintDiagnosticTransport(
                        credential, body, maxResponseChars = 100, protocol = GatewayProtocol.RESPONSES,
                        responseContentType = contentType,
                    )

                    assertSuccessfulDiagnostic(execution.result, "partial [REDACTED]", "public-model", usage = null)
                    assertNull(execution.rawSample, body)
                }
            }
        }
    }

    @Test
    fun responses_absent_or_null_incomplete_details_preserve_completed_fingerprint_samples() = runBlocking {
        val credential = "fingerprint-complete-details-secret"
        for (state in listOf("\"status\":\"completed\",", "")) {
            for (details in listOf("\"incomplete_details\":null,", "")) {
                val response = """{${state}${details}"output_text":"complete $credential","model":"public-model"}"""
                val stream = "data: {\"type\":\"response.completed\",\"response\":$response}"
                for ((body, contentType) in listOf(response to ContentType.Application.Json, stream to ContentType.Text.EventStream)) {
                    val execution = fingerprintDiagnosticTransport(
                        credential, body, maxResponseChars = 100, protocol = GatewayProtocol.RESPONSES,
                        responseContentType = contentType,
                    )

                    assertSuccessfulDiagnostic(execution.result, "complete [REDACTED]", "public-model", usage = null)
                    assertEquals("complete $credential", execution.rawSample, body)
                }
            }
        }
    }

    @Test
    fun diagnostic_response_byte_limit_preserves_complete_json_and_sse_at_the_boundary() = runBlocking {
        val credential = "bounded-response-secret"
        for ((protocol, contentType, body) in listOf(
            Triple(GatewayProtocol.CHAT_COMPLETIONS, ContentType.Application.Json,
                """{"choices":[{"message":{"content":"complete $credential"}}]}"""),
            Triple(GatewayProtocol.RESPONSES, ContentType.Text.EventStream,
                "data: {\"type\":\"response.completed\",\"response\":{\"output_text\":\"complete $credential\"}}\n"),
        )) {
            val padding = " ".repeat(MAX_MODEL_TEST_RESPONSE_BYTES.toInt() - body.toByteArray().size)
            val bounded = fingerprintDiagnosticTransport(
                credential, body + padding, maxResponseChars = 100, protocol = protocol, responseContentType = contentType,
            )
            assertTrue(bounded.result.success)
            assertEquals("complete [REDACTED]", bounded.result.responseText)
            assertEquals("complete $credential", bounded.rawSample)

            val oversized = fingerprintDiagnosticTransport(
                credential, body + padding + " ", maxResponseChars = 100, protocol = protocol, responseContentType = contentType,
            )
            assertFalse(oversized.result.success)
            assertEquals(200, oversized.result.statusCode)
            assertEquals("上游响应超过 1 MiB 上限", oversized.result.errorMessage)
            assertNull(oversized.result.responseText)
            assertNull(oversized.result.usage)
            assertNull(oversized.result.upstreamReportedModel)
            assertNull(oversized.rawSample)
        }
    }

    @Test
    fun oversized_error_responses_never_expose_a_partial_credential_preview() = runBlocking {
        val credential = "bounded-error-secret"
        val body = """{"error":{"message":"$credential"}}""" + " ".repeat(MAX_MODEL_TEST_RESPONSE_BYTES.toInt())
        val execution = fingerprintDiagnosticTransport(
            credential, body, maxResponseChars = 5, status = HttpStatusCode.Unauthorized,
        )
        assertFalse(execution.result.success)
        assertEquals(401, execution.result.statusCode)
        assertEquals("上游响应超过 1 MiB 上限", execution.result.errorMessage)
        assertNull(execution.result.responseText)
        assertNull(execution.rawSample)
    }

    @Test
    fun oversized_open_response_stream_is_cancelled_without_waiting_for_eof() {
        runBlocking {
            val channel = ByteChannel(autoFlush = true)
            val pending = async {
                fingerprintDiagnosticTransport("open-stream-secret", "", maxResponseChars = 100, responseChannel = channel)
            }
            val writer = async {
                runCatching { channel.writeStringUtf8("x".repeat(MAX_MODEL_TEST_RESPONSE_BYTES.toInt() + 1)) }
            }
            try {
                val execution = withTimeout(5_000) { pending.await() }
                assertEquals("上游响应超过 1 MiB 上限", execution.result.errorMessage)
                assertNull(execution.rawSample)
                assertTrue(channel.isClosedForRead)
                withTimeout(5_000) { writer.await() }
            } finally {
                channel.cancel(CancellationException("test cleanup"))
            }
        }
    }

    @Test
    fun caller_cancellation_interrupts_a_bounded_response_read() = runBlocking {
        val channel = ByteChannel(autoFlush = true)
        val readStarted = CompletableDeferred<Unit>()
        val readExited = CompletableDeferred<Unit>()
        val observed = object : ByteReadChannel by channel {
            override suspend fun awaitContent(min: Int): Boolean {
                readStarted.complete(Unit)
                try {
                    return channel.awaitContent(min)
                } finally {
                    readExited.complete(Unit)
                }
            }
        }
        val pending = async {
            fingerprintDiagnosticTransport("cancelled-read-secret", "", maxResponseChars = 100,
                responseChannel = observed)
        }
        try {
            withTimeout(5_000) { readStarted.await() }
            pending.cancel(CancellationException("caller stopped waiting"))
            withTimeout(5_000) { readExited.await(); pending.join() }
            assertTrue(pending.isCancelled)
            assertTrue(channel.isClosedForRead)
        } finally {
            channel.cancel(CancellationException("test cleanup"))
        }
    }

    @Test
    fun bounded_response_read_preserves_the_declared_charset() = runBlocking {
        val body = """{"choices":[{"message":{"content":"réponse charset-secret"}}]}"""
        val execution = fingerprintDiagnosticTransport(
            "charset-secret", "", maxResponseChars = 100,
            responseContentType = ContentType.parse("application/json; charset=ISO-8859-1"),
            responseChannel = ByteReadChannel(body.toByteArray(Charsets.ISO_8859_1)),
        )
        assertTrue(execution.result.success)
        assertEquals("réponse [REDACTED]", execution.result.responseText)
        assertEquals("réponse charset-secret", execution.rawSample)
    }

    @Test
    fun fingerprint_execution_returns_bounded_original_response_with_redacted_result() = runBlocking {
        val credential = "fingerprint-synthetic-secret"
        val originalText = "sample $credential remains unchanged"
        val body = """{"choices":[{"message":{"content":"$originalText"}}],"model":"model-$credential"}"""

        val execution = fingerprintDiagnosticTransport(credential, body, maxResponseChars = 24)
        val result = execution.result
        val rawSample = execution.rawSample

        assertEquals(originalText.take(24), rawSample)
        assertFalse(result.responseText.orEmpty().contains(credential))
        assertFalse(result.upstreamReportedModel.orEmpty().contains(credential))
        assertEquals("sample [REDACTED] remain", result.responseText)
        assertEquals("model-[REDACTED]", result.upstreamReportedModel)
    }

    @Test
    fun fingerprint_sample_callback_captures_partial_text_from_failed_responses_stream_without_exposing_it() = runBlocking {
        val credential = "fingerprint-failed-stream-secret"
        val body = listOf(
            "event: response.output_text.delta",
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial $credential\"}",
            "event: response.failed",
            "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"message\":\"denied $credential\"}}}",
        ).joinToString("\n")

        val execution = fingerprintDiagnosticTransport(
            credential,
            body,
            maxResponseChars = 100,
            protocol = GatewayProtocol.RESPONSES,
            responseContentType = ContentType.Text.EventStream,
        )
        val result = execution.result
        val rawSample = execution.rawSample

        assertFalse(result.success)
        assertNull(rawSample)
        assertEquals("partial [REDACTED]", result.responseText)
        assertFalse(result.errorMessage.orEmpty().contains(credential))
    }

    @Test
    fun fingerprint_sample_callback_captures_partial_text_before_an_error_responses_event() = runBlocking {
        assertFailedResponsesPartialTextIsCaptured(
            suffix = "error",
            terminalEvent = "error",
            terminalPayload = """{"type":"error","error":{"message":"denied fingerprint-error-secret"}}""",
        )
    }

    @Test
    fun fingerprint_sample_callback_captures_partial_text_before_incomplete_responses_event() = runBlocking {
        assertFailedResponsesPartialTextIsCaptured(
            suffix = "incomplete",
            terminalEvent = "response.incomplete",
            terminalPayload = """{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"}}}""",
        )
    }

    @Test
    fun fingerprint_sample_callback_captures_partial_text_when_responses_terminal_event_is_missing() = runBlocking {
        assertFailedResponsesPartialTextIsCaptured(
            suffix = "missing-terminal",
            terminalEvent = null,
            terminalPayload = null,
        )
    }

    private suspend fun assertFailedResponsesPartialTextIsCaptured(
        suffix: String,
        terminalEvent: String?,
        terminalPayload: String?,
    ) {
        val credential = "fingerprint-$suffix-secret"
        val body = buildList {
            add("event: response.output_text.delta")
            add("data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial $credential\"}")
            if (terminalEvent != null && terminalPayload != null) {
                add("event: $terminalEvent")
                add("data: $terminalPayload")
            }
        }.joinToString("\n")

        val execution = fingerprintDiagnosticTransport(
            credential,
            body,
            maxResponseChars = 100,
            protocol = GatewayProtocol.RESPONSES,
            responseContentType = ContentType.Text.EventStream,
        )
        val result = execution.result
        val rawSample = execution.rawSample

        assertFalse(result.success)
        assertNull(rawSample)
        assertEquals("partial [REDACTED]", result.responseText)
        assertFalse(result.errorMessage.orEmpty().contains(credential))
    }

    @Test
    fun nested_non_string_json_error_scalars_are_redacted_in_display_previews() = runBlocking {
        val cases = listOf(
            Triple("7", """{"detail":{"count":7,"enabled":false,"note":null}}""", """{"detail":{"count":"[REDACTED]","enabled":false,"note":null}}"""),
            Triple("true", """{"detail":{"enabled":true,"count":7,"note":null}}""", """{"detail":{"enabled":"[REDACTED]","count":7,"note":null}}"""),
            Triple("null", """{"detail":{"note":null,"count":7,"enabled":false}}""", """{"detail":{"note":"[REDACTED]","count":7,"enabled":false}}"""),
        )

        for ((credential, body, expected) in cases) {
            for (oauth in listOf(false, true)) {
                val result = diagnosticTransport(
                    credential,
                    oauth = oauth,
                    responseBody = body,
                    status = HttpStatusCode.BadGateway,
                )

                assertFailedDiagnostic(result, 502, expected)
                assertFalse(result.errorMessage.orEmpty().contains(credential), "credential=$credential")
            }
        }
    }

    @Test
    fun fingerprint_sample_callback_does_not_capture_partial_text_from_non_2xx_responses() = runBlocking {
        val credential = "fingerprint-non-2xx-secret"
        val body = listOf(
            "event: response.output_text.delta",
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial $credential\"}",
            "event: response.failed",
            "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"message\":\"denied $credential\"}}}",
        ).joinToString("\n")

        val execution = fingerprintDiagnosticTransport(
            credential,
            body,
            maxResponseChars = 100,
            protocol = GatewayProtocol.RESPONSES,
            responseContentType = ContentType.Text.EventStream,
            status = HttpStatusCode.BadGateway,
        )
        val result = execution.result
        val rawSample = execution.rawSample

        assertFalse(result.success)
        assertNull(result.responseText)
        assertNull(rawSample)
        assertFalse(result.errorMessage.orEmpty().contains(credential))
    }

    @Test
    fun duplicate_json_error_fields_never_fall_back_to_the_unsanitized_response() = runBlocking {
        val credential = "synthetic-duplicate-secret"
        // The JSON parser keeps only the last duplicate key, while the earlier value remains in the raw body.
        val body = """{"detail":"$credential","detail":"ordinary"}"""

        val result = diagnosticTransport(
            credential,
            responseBody = body,
            status = HttpStatusCode.BadGateway,
        )

        assertFailedDiagnostic(result, 502, """{"detail":"ordinary"}""")
        assertFalse(result.errorMessage.orEmpty().contains(credential))
    }

    @Test
    fun failed_and_incomplete_responses_keep_usage_model_status_and_safe_partial_text() = runBlocking {
        val credential = "synthetic-terminal-secret"
        val usage = """{"input_tokens":7,"output_tokens":3,"total_tokens":10,"input_tokens_details":{"cached_tokens":2},"output_tokens_details":{"reasoning_tokens":1}}"""
        for (state in listOf("failed", "incomplete")) {
            val response = """{"status":"$state","model":"model-$credential","output":[{"content":[{"text":"partial $credential"}]}],"usage":$usage,"error":{"message":"denied $credential"}}"""
            val stream = listOf(
                """data: {"type":"response.output_text.delta","delta":"old partial"}""",
                """data: {"type":"response.$state","response":$response}""",
            ).joinToString("\n")
            val parsed = requireNotNull(parseResponsesStreamText(stream))
            assertEquals(state, parsed["status"]?.jsonPrimitive?.content)
            assertEquals("model-$credential", parsed["model"]?.jsonPrimitive?.content)
            assertEquals(Json.parseToJsonElement(usage), parsed["usage"])
            assertEquals("partial $credential", collectText(parsed["output_text"]))
            assertEquals("denied $credential", parsed["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content)
            assertNull(parsed["stream_truncated"])

            for ((body, contentType) in listOf(response to ContentType.Application.Json, stream to ContentType.Text.EventStream)) {
                for (oauth in listOf(false, true)) {
                    val result = diagnosticTransport(
                        credential, protocol = GatewayProtocol.RESPONSES, oauth = oauth,
                        responseBody = body, contentType = contentType,
                    )
                    assertFailedDiagnostic(
                        result, 200, "denied [REDACTED]", responseText = "partial [REDACTED]",
                        usage = Usage(input = 7, output = 3, cached = 2, reasoning = 1, total = 10),
                        reportedModel = "model-[REDACTED]",
                    )
                }
                val execution = fingerprintDiagnosticTransport(
                    credential, body, maxResponseChars = 100, protocol = GatewayProtocol.RESPONSES,
                    responseContentType = contentType,
                )
                assertFalse(execution.result.success)
                assertNotNull(execution.result.usage)
                assertNull(execution.rawSample)
            }
        }
    }

    @Test
    fun incomplete_responses_without_error_keep_usage_and_terminal_partial_output() = runBlocking {
        val credential = "synthetic-incomplete-secret"
        val response = """{"status":"incomplete","model":"public-model","output_text":"partial $credential","usage":{"input_tokens":7,"output_tokens":3,"total_tokens":10},"incomplete_details":{"reason":"max_output_tokens"}}"""
        val stream = """data: {"type":"response.incomplete","response":$response}"""
        for ((body, contentType, message) in listOf(
            Triple(response, ContentType.Application.Json, "上游 Responses 尚未成功完成：incomplete"),
            Triple(stream, ContentType.Text.EventStream, "响应因达到输出上限被截断（max_output_tokens）"),
        )) {
            val execution = fingerprintDiagnosticTransport(
                credential, body, maxResponseChars = 100, protocol = GatewayProtocol.RESPONSES,
                responseContentType = contentType,
            )
            assertFailedDiagnostic(
                execution.result, 200, message, responseText = "partial [REDACTED]",
                usage = Usage(input = 7, output = 3, cached = null, reasoning = null, total = 10),
                reportedModel = "public-model",
            )
            assertNull(execution.rawSample)
        }
    }

    @Test
    fun encoded_credentials_are_redacted_before_success_error_and_transport_limits() = runBlocking {
        val credential = "sk-Synthetic /+é中\"\\"
        for (encoded in encodedCredentialEchoes(credential)) {
            assertFalse(encoded.contains(credential))
            for (oauth in listOf(false, true)) {
                for ((textOffset, modelOffset) in listOf(0 to 0, 24 to 193, 34 to 203)) {
                    val prefix = "t".repeat(textOffset)
                    val modelPrefix = "m".repeat(modelOffset)
                    val success = diagnosticTransport(
                        credential, oauth = oauth, maxResponseChars = 31,
                        responseBody = """{"choices":[{"message":{"content":${JsonPrimitive("$prefix$encoded tail")}}}],"model":${JsonPrimitive("$modelPrefix$encoded tail")},"usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}""",
                    )
                    assertSuccessfulDiagnostic(
                        success, (prefix + "[REDACTED] tail").take(31), (modelPrefix + "[REDACTED] tail").take(200),
                        usage = Usage(input = 7, output = 3, cached = null, reasoning = null, total = 10),
                    )
                }
                for (offset in listOf(0, 493, 503)) {
                    val prefix = "e".repeat(offset)
                    for ((body, contentType) in listOf(
                        """{"error":{"message":${JsonPrimitive("$prefix$encoded tail")}}}""" to ContentType.Application.Json,
                        "$prefix$encoded tail" to ContentType.Text.Plain,
                    )) {
                        val result = diagnosticTransport(
                            credential, oauth = oauth, responseBody = body,
                            status = HttpStatusCode.BadGateway, contentType = contentType,
                        )
                        assertFailedDiagnostic(result, 502, (prefix + "[REDACTED] tail").take(500))
                    }
                }
                for (offset in listOf(0, 193, 203)) {
                    val prefix = "e".repeat(offset)
                    val result = diagnosticTransport(
                        credential, oauth = oauth, failure = IllegalStateException("$prefix$encoded tail"),
                    )
                    assertFailedDiagnostic(result, null, "无法连接上游：" + (prefix + "[REDACTED] tail").take(200))
                }
                val preview = diagnosticTransport(
                    credential, oauth = oauth, status = HttpStatusCode.BadGateway,
                    responseBody = """{"outer":[{${JsonPrimitive(encoded)}:${JsonPrimitive("before $encoded after")},"status":502,"enabled":false,"note":null}]}""",
                )
                assertFailedDiagnostic(preview, 502, """{"outer":[{"[REDACTED]":"before [REDACTED] after","status":502,"enabled":false,"note":null}]}""")
            }
        }
    }

    @Test
    fun encoded_short_numeric_credentials_do_not_redact_individual_encoding_characters() = runBlocking {
        for (oauth in listOf(false, true)) {
            val result = diagnosticTransport(
                "7", oauth = oauth,
                responseBody = """{"choices":[{"message":{"content":${JsonPrimitive("keep u003 0 3 %3; remove %37 and \\u0037")}}}],"model":"public-model","usage":{"prompt_tokens":"7","completion_tokens":"3","total_tokens":"10"}}""",
            )
            assertSuccessfulDiagnostic(
                result, "keep u003 0 3 %3; remove [REDACTED] and [REDACTED]", "public-model",
                usage = Usage(input = 7, output = 3, cached = null, reasoning = null, total = 10),
            )
            val preview = diagnosticTransport(
                "7", oauth = oauth, status = HttpStatusCode.BadGateway,
                responseBody = """{"detail":"%37","count":3,"enabled":false,"note":null}""",
            )
            assertFailedDiagnostic(preview, 502, """{"detail":"[REDACTED]","count":3,"enabled":false,"note":null}""")
        }
    }

    @Test
    fun display_normalization_precedes_redaction_and_limits_for_both_entry_points() = runBlocking {
        val credential = "sk-synthetic-display-secret"
        val removed = "\u0000\u001b\r\u007f\u0085\u202e\u200d"
        for (echo in listOf(credential, mixedCredentialEcho(credential))) {
            val split = echo.toList().joinToString(removed)
            for (oauth in listOf(false, true)) {
                val prefix = "t".repeat(24)
                val success = diagnosticTransport(
                    credential, oauth = oauth, maxResponseChars = 31,
                    responseBody = """{"choices":[{"message":{"content":${JsonPrimitive("$prefix$split tail")}}}],"model":${JsonPrimitive("model-$split")},"usage":{"prompt_tokens":"7","completion_tokens":"3","total_tokens":"10"}}""",
                )
                assertSuccessfulDiagnostic(
                    success, (prefix + "[REDACTED] tail").take(31), "model-[REDACTED]",
                    usage = Usage(input = 7, output = 3, cached = null, reasoning = null, total = 10),
                )
                for ((body, type) in listOf(
                    "denied $split tail" to ContentType.Text.Plain,
                    """{"error":{"message":${JsonPrimitive("denied $split tail")}}}""" to ContentType.Application.Json,
                )) {
                    val failure = diagnosticTransport(
                        credential, oauth = oauth, responseBody = body,
                        status = HttpStatusCode.BadGateway, contentType = type,
                    )
                    assertFailedDiagnostic(failure, 502, "denied [REDACTED] tail")
                }
                val preview = diagnosticTransport(
                    credential, oauth = oauth, status = HttpStatusCode.BadGateway,
                    responseBody = """{${JsonPrimitive(split)}:${JsonPrimitive("before $split after")},"count":3,"enabled":false,"note":null}""",
                )
                assertFailedDiagnostic(preview, 502, """{"[REDACTED]":"before [REDACTED] after","count":3,"enabled":false,"note":null}""")
                val exception = diagnosticTransport(
                    credential, oauth = oauth, failure = IllegalStateException("denied $split tail"),
                )
                assertFailedDiagnostic(exception, null, "无法连接上游：denied [REDACTED] tail")
            }
        }
    }

    @Test
    fun model_normalization_also_removes_line_and_tab_separators_before_redacting() = runBlocking {
        val credential = "sk-synthetic-model-secret"
        val split = credential.toList().joinToString("\n\t")
        for (oauth in listOf(false, true)) {
            val result = diagnosticTransport(
                credential, oauth = oauth,
                responseBody = """{"choices":[{"message":{"content":${JsonPrimitive("keep line\n\tbreak")}}}],"model":${JsonPrimitive("model-$split")}}""",
            )
            assertSuccessfulDiagnostic(result, "keep line\n\tbreak", "model-[REDACTED]", usage = null)
        }
    }

    @Test
    fun fingerprint_keeps_original_nul_split_sample_but_sanitizes_all_display_values() = runBlocking {
        val credential = "sk-synthetic-fingerprint-secret"
        val split = credential.toList().joinToString("\u0000\u202e")
        val original = "before $split after"
        val execution = fingerprintDiagnosticTransport(
            credential,
            """{"choices":[{"message":{"content":${JsonPrimitive(original)}}}],"model":${JsonPrimitive("model-$split")}}""",
            maxResponseChars = original.length,
        )
        assertEquals(original, execution.rawSample)
        assertSuccessfulDiagnostic(execution.result, "before [REDACTED] after", "model-[REDACTED]", usage = null)
        assertEquals("before [REDACTED] after", execution.safeText(original))
        assertNull(execution.safeText(null))
        assertFalse(execution.toString().contains(credential))
    }

    @Test
    fun long_oauth_and_saved_tokens_redact_raw_encoded_and_mixed_echoes_without_regex_recursion() = runBlocking {
        val credential = "oauth-" + "Ab9_".repeat(3_000) + "/é中😀\"\\"
        val percent = credential.toByteArray(Charsets.UTF_8).joinToString("") {
            "%" + (it.toInt() and 0xff).toString(16).padStart(2, '0').uppercase()
        }
        val echoes = listOf(credential, percent, unicodeEscapedCredential(credential), mixedCredentialEcho(credential))
        for (echo in echoes) {
            for (oauth in listOf(false, true)) {
                val success = diagnosticTransport(
                    credential, oauth = oauth, maxResponseChars = 31,
                    responseBody = """{"choices":[{"message":{"content":${JsonPrimitive("before $echo after")}}}],"model":${JsonPrimitive("model-$echo")},"usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}""",
                )
                assertSuccessfulDiagnostic(
                    success, "before [REDACTED] after", "model-[REDACTED]",
                    usage = Usage(input = 7, output = 3, cached = null, reasoning = null, total = 10),
                )
                val error = diagnosticTransport(
                    credential, oauth = oauth, status = HttpStatusCode.BadGateway,
                    responseBody = "denied $echo tail", contentType = ContentType.Text.Plain,
                )
                assertFailedDiagnostic(error, 502, "denied [REDACTED] tail")
                val exception = diagnosticTransport(
                    credential, oauth = oauth, failure = IllegalStateException("denied $echo tail"),
                )
                assertFailedDiagnostic(exception, null, "无法连接上游：denied [REDACTED] tail")
            }
        }
    }

    @Test
    fun mixed_encodings_preserve_literal_case_and_handle_ambiguous_encoding_prefixes() = runBlocking {
        val credential = "%41\\u0041 A/+é中😀\"\\"
        val mixed = mixedCredentialEcho(credential)
        for (oauth in listOf(false, true)) {
            val result = diagnosticTransport(
                credential, oauth = oauth,
                responseBody = """{"choices":[{"message":{"content":${JsonPrimitive("before $mixed after")}}}],"model":"public-model"}""",
            )
            assertSuccessfulDiagnostic(result, "before [REDACTED] after", "public-model", usage = null)
            val caseSensitive = diagnosticTransport(
                "AbC", oauth = oauth,
                responseBody = """{"choices":[{"message":{"content":"abc %61b%63 AbC %41b%43"}}],"model":"public-model"}""",
            )
            assertSuccessfulDiagnostic(caseSensitive, "abc %61b%63 [REDACTED] [REDACTED]", "public-model", usage = null)
        }
    }

    @Test
    fun repeatedCredentialPrefixesHaveBoundedRedactionWorkForMaximumResponses() {
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5)) {
            runBlocking {
                val credential = "a".repeat(1_023) + "b"
                val empty = """{"choices":[{"message":{"content":""}}],"model":"public-model"}"""
                val content = "a".repeat(MAX_MODEL_TEST_RESPONSE_BYTES.toInt() - empty.length)
                val body = empty.replace("\"content\":\"\"", "\"content\":" + JsonPrimitive(content))
                assertEquals(MAX_MODEL_TEST_RESPONSE_BYTES.toInt(), body.toByteArray().size)
                for (oauth in listOf(false, true)) {
                    val result = diagnosticTransport(credential, oauth = oauth, responseBody = body)
                    assertSuccessfulDiagnostic(result, "[REDACTED]", "[REDACTED]", usage = null)
                }
            }
        }
    }

    @Test
    fun repeatedCredentialPrefixesShareOneBudgetAcrossJsonPreviewsAndFingerprintOutput() {
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5)) {
            runBlocking {
                val credential = "a".repeat(1_023) + "b"
                val repeated = "a".repeat(2_048)
                val bodies = listOf(
                    kotlinx.serialization.json.buildJsonObject {
                        repeat(500) { index -> put("field-" + index, JsonPrimitive(repeated)) }
                        put("tail", JsonPrimitive(credential))
                    }.toString(),
                    kotlinx.serialization.json.buildJsonObject {
                        repeat(500) { index -> put(repeated + index, JsonPrimitive(0)) }
                        put("tail", JsonPrimitive(credential))
                    }.toString(),
                    kotlinx.serialization.json.buildJsonObject {
                        put("items", kotlinx.serialization.json.buildJsonArray {
                            repeat(500) { add(JsonPrimitive(repeated)) }
                            add(JsonPrimitive(credential))
                        })
                    }.toString(),
                )
                for (body in bodies) {
                    assertTrue(body.toByteArray().size <= MAX_MODEL_TEST_RESPONSE_BYTES)
                    for (oauth in listOf(false, true)) {
                        val result = diagnosticTransport(credential, oauth = oauth, responseBody = body, status = HttpStatusCode.BadGateway)
                        assertFailedDiagnostic(result, 502, "[REDACTED]")
                    }
                }
                val execution = fingerprintDiagnosticTransport(credential, bodies.first(), maxResponseChars = 100, status = HttpStatusCode.BadGateway)
                assertEquals("[REDACTED]", execution.safeText("later uninspected output"))
                assertEquals("[REDACTED]", execution.safeText(credential))
                assertNull(execution.rawSample)
            }
        }
    }

    private fun mixedCredentialEcho(credential: String): String =
        credential.codePoints().toArray().mapIndexed { index, codePoint ->
            val literal = String(Character.toChars(codePoint))
            when (index % 4) {
                0 -> literal
                1 -> literal.toByteArray(Charsets.UTF_8).joinToString("") {
                    "%" + (it.toInt() and 0xff).toString(16).padStart(2, '0').uppercase()
                }
                2 -> unicodeEscapedCredential(literal)
                else -> JsonPrimitive(literal).toString().drop(1).dropLast(1).replace("/", "\\/")
            }
        }.joinToString("")

    private fun encodedCredentialEchoes(credential: String): List<String> {
        val url = java.net.URLEncoder.encode(credential, Charsets.UTF_8)
        val lowerUrl = Regex("%[0-9A-F]{2}").replace(url) { it.value.lowercase() }
        val fullUrl = credential.toByteArray(Charsets.UTF_8).joinToString("") {
            "%" + (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        val unicode = unicodeEscapedCredential(credential)
        val upperUnicode = Regex("\\\\u([0-9a-f]{4})").replace(unicode) { "\\u" + it.groupValues[1].uppercase() }
        val json = JsonPrimitive(credential).toString().drop(1).dropLast(1)
        return listOf(url, lowerUrl, url.replace("+", "%20"), fullUrl, unicode, upperUnicode, json, json.replace("/", "\\/")).distinct()
    }

    private suspend fun fingerprintDiagnosticTransport(
        credential: String,
        responseBody: String,
        maxResponseChars: Int,
        protocol: GatewayProtocol = GatewayProtocol.CHAT_COMPLETIONS,
        responseContentType: ContentType = ContentType.Application.Json,
        status: HttpStatusCode = HttpStatusCode.OK,
        timeoutMs: Long = 45_000,
        failure: Throwable? = null,
        responseDelayMs: Long = 0,
        requestEntered: CompletableDeferred<Unit>? = null,
        requireCompleteSample: Boolean = true,
        responseChannel: ByteReadChannel? = null,
    ): ModelConnectivityExecution {
        val channel = ChannelRecord(
            id = "fingerprint-channel", name = "fingerprint-channel",
            baseUrl = "https://diagnostic.example.test", authType = "bearer",
            protocols = listOf(protocol), enabled = true,
            createdAt = 0, updatedAt = 0,
        )
        val savedKey = ChannelKeyRecord(
            id = "fingerprint-key", channelId = channel.id, name = "synthetic-key",
            credentialCiphertext = "synthetic-ciphertext", credentialNonce = "synthetic-nonce",
            credentialKeyVersion = 1, state = ChannelKeyState.ACTIVE, priority = 0,
            createdAt = 0, updatedAt = 0,
        )
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    assertEquals("Bearer $credential", request.headers[HttpHeaders.Authorization])
                    failure?.let { throw it }
                    requestEntered?.complete(Unit)
                    if (responseDelayMs > 0) delay(responseDelayMs)
                    respond(responseChannel ?: ByteReadChannel(responseBody), status, headersOf(HttpHeaders.ContentType, responseContentType.toString()))
                }
            }
        }
        return client.use {
            testSavedChannelModelConnectivity(
                channel = channel,
                credentialRef = savedKey,
                model = "diagnostic-model",
                protocol = protocol,
                prompt = "say ok",
                credentials = KeyCredentialResolver { credential },
                httpClient = it,
                timeoutMs = timeoutMs,
                maxOutputTokens = 128,
                maxResponseChars = maxResponseChars,
                globalOverrides = UpstreamRequestOverrides(),
                headerProfiles = UpstreamHeaderProfiles.defaults,
                isolatedIqRequest = true,
                captureRawSample = true,
                requireCompleteSample = requireCompleteSample,
            )
        }
    }

    private suspend fun diagnosticTransport(
        credential: String,
        protocol: GatewayProtocol = GatewayProtocol.CHAT_COMPLETIONS,
        oauth: Boolean = false,
        responseBody: String = "",
        status: HttpStatusCode = HttpStatusCode.OK,
        contentType: ContentType = ContentType.Application.Json,
        maxResponseChars: Int = MAX_RESPONSE_CHARS,
        failure: Throwable? = null,
        provider: OAuthProvider? = null,
    ): ModelConnectivityTestResult {
        val channel = ChannelRecord(
            id = "diagnostic-channel",
            name = "diagnostic-channel",
            baseUrl = "https://diagnostic.example.test",
            authType = "bearer",
            protocols = listOf(protocol),
            enabled = true,
            createdAt = 0,
            updatedAt = 0,
            kind = if (oauth) ChannelKind.PROVIDER else ChannelKind.CUSTOM,
            provider = if (oauth) provider ?: OAuthProvider.CHATGPT else null,
        )
        val savedKey = ChannelKeyRecord(
            id = "diagnostic-key",
            channelId = channel.id,
            name = "synthetic-key",
            credentialCiphertext = "synthetic-ciphertext",
            credentialNonce = "synthetic-nonce",
            credentialKeyVersion = 1,
            state = ChannelKeyState.ACTIVE,
            priority = 0,
            createdAt = 0,
            updatedAt = 0,
        )
        var requests = 0
        var resolutions = 0
        return HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requests += 1
                    assertEquals("diagnostic.example.test", request.url.host)
                    assertEquals("Bearer $credential", request.headers[HttpHeaders.Authorization])
                    failure?.let { throw it }
                    respond(responseBody, status, headersOf(HttpHeaders.ContentType, contentType.toString()))
                }
            }
        }.use { client ->
            val result = if (oauth) {
                prepareOAuthAccountModelConnectivity(
                    channel = channel,
                    accessToken = credential,
                    project = if (provider == OAuthProvider.ANTIGRAVITY) "synthetic-project" else null,
                    model = "diagnostic-model",
                    protocol = protocol,
                    prompt = "say ok",
                    httpClient = client,
                    maxResponseChars = maxResponseChars,
                )()
            } else {
                testChannelModelConnectivity(
                    channel = channel,
                    credentialRef = savedKey,
                    model = "diagnostic-model",
                    protocol = protocol,
                    prompt = "say ok",
                    credentials = KeyCredentialResolver {
                        assertEquals(savedKey, it)
                        resolutions += 1
                        credential
                    },
                    httpClient = client,
                    maxResponseChars = maxResponseChars,
                )
            }
            assertEquals(1, requests, "The public entry must send one request through the neutral transport")
            assertEquals(if (oauth) 0 else 1, resolutions, "The channel entry must resolve its saved credential")
            result
        }
    }

    private fun assertSuccessfulDiagnostic(
        result: ModelConnectivityTestResult,
        responseText: String,
        reportedModel: String,
        usage: Usage? = Usage(input = 7, output = 3, cached = 2, reasoning = 1, total = 10),
    ) {
        assertTrue(result.success)
        assertEquals(200, result.statusCode)
        assertEquals(responseText, result.responseText)
        assertEquals(reportedModel, result.upstreamReportedModel)
        assertEquals(usage, result.usage)
        assertNull(result.errorMessage)
        assertTrue(result.latencyMs >= 0)
    }

    private fun assertFailedDiagnostic(
        result: ModelConnectivityTestResult,
        statusCode: Int?,
        errorMessage: String,
        responseText: String? = null,
        usage: Usage? = null,
        reportedModel: String? = null,
    ) {
        assertFalse(result.success)
        assertEquals(statusCode, result.statusCode)
        assertEquals(errorMessage, result.errorMessage)
        assertEquals(responseText, result.responseText)
        assertEquals(reportedModel, result.upstreamReportedModel)
        assertEquals(usage, result.usage)
        assertTrue(result.latencyMs >= 0)
    }

    private fun unicodeEscapedCredential(credential: String): String =
        credential.map { "\\u${it.code.toString(16).padStart(4, '0')}" }.joinToString("")

    private fun reply(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
