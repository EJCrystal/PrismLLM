package dev.soven.prismllm.proxy

import dev.soven.prismllm.shared.error.GatewayError
import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.shared.domain.UpstreamProtocolOverrides
import dev.soven.prismllm.shared.domain.UpstreamRequestOverrides
import dev.soven.prismllm.channel.domain.UpstreamHeaderProfile
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("unit")
class UpstreamRequestPolicyTest {
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test
    fun `selects presets from outbound protocol not auth scheme`() {
        val expected = mapOf(
            GatewayProtocol.MESSAGES to "claude-cli/",
            GatewayProtocol.CHAT_COMPLETIONS to "codex_cli_rs/",
            GatewayProtocol.RESPONSES to "codex_cli_rs/",
            GatewayProtocol.GEMINI to "google-genai-sdk/",
        )
        expected.forEach { (protocol, prefix) ->
            val builder = HttpRequestBuilder()
            UpstreamRequestPolicy(UpstreamRequestOverrides(), UpstreamRequestOverrides(), protocol, "bearer")
                .applyHeaders(builder)
            assertTrue(builder.headers["User-Agent"]!!.startsWith(prefix))
        }
    }

    @Test
    fun `uses the supplied dynamic catalog snapshot instead of bundled versions`() {
        val profiles = UpstreamHeaderProfiles.defaults.map { profile ->
            if (profile.id == "codex") {
                profile.copy(
                    version = "9.9.9",
                    headers = mapOf(
                        "User-Agent" to "codex_cli_rs/9.9.9 (Linux; x86_64)",
                        "originator" to "codex_cli_rs",
                        "version" to "9.9.9",
                    ),
                )
            } else profile
        }
        val builder = HttpRequestBuilder()
        UpstreamRequestPolicy(
            UpstreamRequestOverrides(), UpstreamRequestOverrides(), GatewayProtocol.RESPONSES, "bearer", profiles,
        ).applyHeaders(builder)
        assertEquals(listOf("codex_cli_rs/9.9.9 (Linux; x86_64)"), builder.headers.getAll("User-Agent"))
        assertEquals("9.9.9", builder.headers["version"])
    }

    @Test
    fun `channel and protocol layers override global headers without duplicate casing`() {
        val global = UpstreamRequestOverrides(
            userAgent = "global-UA", headers = mapOf("X-Feature" to "global", "X-Remove" to "remove me"),
            protocols = mapOf("responses" to UpstreamProtocolOverrides(headers = mapOf("x-feature" to "global-protocol"))),
        )
        val channel = UpstreamRequestOverrides(
            userAgent = "channel-UA", headers = mapOf("x-feature" to "channel", "X-REMOVE" to null),
            protocols = mapOf("responses" to UpstreamProtocolOverrides(userAgent = "channel-protocol-UA")),
        )
        val builder = HttpRequestBuilder()
        builder.applyUpstreamAuthentication("upstream-secret", "bearer", GatewayProtocol.RESPONSES)
        builder.headers.append("traceparent", "trace")
        UpstreamRequestPolicy(global, channel, GatewayProtocol.RESPONSES, "bearer").applyHeaders(builder)
        assertEquals(listOf("channel-protocol-UA"), builder.headers.getAll("user-agent"))
        assertEquals(listOf("channel"), builder.headers.getAll("X-Feature"))
        assertNull(builder.headers["X-Remove"])
        assertEquals("Bearer upstream-secret", builder.headers["Authorization"])
        assertEquals("trace", builder.headers["traceparent"])
    }

    @Test
    fun `final selected profile does not retain headers from a replaced profile`() {
        val builder = HttpRequestBuilder()
        UpstreamRequestPolicy(
            UpstreamRequestOverrides(headerProfile = "claude"),
            UpstreamRequestOverrides(headerProfile = "antigravity"), GatewayProtocol.GEMINI, "bearer",
        ).applyHeaders(builder)
        assertTrue(builder.headers["User-Agent"]!!.startsWith("antigravity/"))
        assertNull(builder.headers["x-app"])
        assertNull(builder.headers["originator"])
    }

    @Test
    fun `Codex version headers follow a custom CLI UA unless explicitly overridden`() {
        val builder = HttpRequestBuilder()
        UpstreamRequestPolicy(
            UpstreamRequestOverrides(userAgent = "codex_cli_rs/0.150.0 (Linux; x86_64)"),
            UpstreamRequestOverrides(), GatewayProtocol.RESPONSES, "bearer",
        ).applyHeaders(builder)
        assertEquals("0.150.0", builder.headers["version"])
        assertEquals("codex_cli_rs", builder.headers["originator"])
        UpstreamRequestPolicy(
            UpstreamRequestOverrides(userAgent = "codex_cli_rs/0.150.0 (Linux; x86_64)"),
            UpstreamRequestOverrides(headers = mapOf("Version" to "custom-version")), GatewayProtocol.RESPONSES, "bearer",
        ).applyHeaders(builder)
        assertEquals("custom-version", builder.headers["version"])
    }

    @Test
    fun `Codex identity headers recognize originator case-insensitively`() {
        val profiles = UpstreamHeaderProfiles.defaults.map { profile ->
            if (profile.id == "codex") {
                profile.copy(
                    headers = mapOf(
                        "User-Agent" to "codex_cli_rs/9.9.9 (Linux; x86_64)",
                        "Originator" to "codex_cli_rs",
                    ),
                )
            } else profile
        }
        val builder = HttpRequestBuilder()
        UpstreamRequestPolicy(
            UpstreamRequestOverrides(userAgent = "codex_cli_rs/0.150.0 (Linux; x86_64)"),
            UpstreamRequestOverrides(), GatewayProtocol.RESPONSES, "bearer", profiles,
        ).applyHeaders(builder)
        assertEquals("0.150.0", builder.headers["version"])
        assertEquals("codex_cli_rs", builder.headers["originator"])
    }

    @Test
    fun `merge patches delete fields replace arrays and preserve original across attempts`() {
        val original = json("{\"model\":\"public\",\"stream\":true,\"store\":true,\"reasoning\":{\"effort\":\"low\",\"summary\":\"auto\"},\"tools\":[1]}")
        val global = UpstreamRequestOverrides(body = json("{\"store\":null,\"reasoning\":{\"effort\":\"high\"},\"tools\":[2,3]}"))
        val channel = UpstreamRequestOverrides(
            body = json("{\"reasoning\":{\"summary\":null},\"temperature\":0.3}"),
            protocols = mapOf("responses" to UpstreamProtocolOverrides(body = json("{\"reasoning\":{\"effort\":\"medium\"}}"))),
        )
        val rewritten = UpstreamRequestPolicy(global, channel, GatewayProtocol.RESPONSES, "bearer").body(original, "upstream-A")
        assertEquals("upstream-A", rewritten["model"]?.jsonPrimitive?.content)
        assertEquals(json("{\"effort\":\"medium\"}"), rewritten["reasoning"])
        assertEquals("[2,3]", rewritten["tools"].toString())
        assertFalse(rewritten.containsKey("store"))
        assertEquals("public", original["model"]?.jsonPrimitive?.content)
        assertTrue(original.containsKey("store"))
        val next = UpstreamRequestPolicy(global, UpstreamRequestOverrides(), GatewayProtocol.RESPONSES, "bearer").body(original, "upstream-B")
        assertEquals("auto", next["reasoning"]?.jsonObject?.get("summary")?.jsonPrimitive?.content)
    }

    @Test
    fun `extra body fills absent fields but preserves client values including null and empty`() {
        val policy = UpstreamRequestPolicy(
            UpstreamRequestOverrides(),
            UpstreamRequestOverrides(extraBody = json("""{"prompt_cache_key":"${'$'}{sessionId}","metadata":{"nested":["${'$'}{threadId}","${'$'}{subagentId}"]},"explicit_null":null}""")),
            GatewayProtocol.RESPONSES, "bearer", conversation = UpstreamConversation("session", "thread", "agent"),
        )
        val first = policy.body(json("{}"), "m")
        assertEquals("session", first["prompt_cache_key"]?.jsonPrimitive?.content)
        assertEquals(json("""{"nested":["thread","agent"]}"""), first["metadata"])
        assertTrue(first.containsKey("explicit_null"))
        for (value in listOf("\"caller\"", "\"\"", "null")) {
            val original = json("""{"prompt_cache_key":$value,"metadata":{"client":true}}""")
            val result = policy.body(original, "m")
            assertEquals(original["prompt_cache_key"], result["prompt_cache_key"])
            assertEquals(original["metadata"], result["metadata"], "defaults replace no existing top-level value")
        }
    }

    @Test
    fun `extra body resolves normalized field precedence before applying and forces whole values`() {
        val global = UpstreamRequestOverrides(
            extraBody = json("""{"!prompt_cache_key":"global-forced","choice":"global"}"""),
            protocols = mapOf("responses" to UpstreamProtocolOverrides(extraBody = json("""{"choice":"global-protocol"}"""))),
        )
        val channel = UpstreamRequestOverrides(
            body = json("""{"patched":"kept"}"""),
            extraBody = json("""{"prompt_cache_key":"channel","choice":"channel","patched":"unused"}"""),
            protocols = mapOf("responses" to UpstreamProtocolOverrides(extraBody = json("""{"choice":"channel-protocol"," !metadata ":{"replacement":true},"!drop":null}"""))),
        )
        val policy = UpstreamRequestPolicy(global, channel, GatewayProtocol.RESPONSES, "bearer")
        val result = policy.body(json("""{"prompt_cache_key":"caller","metadata":{"original":true},"drop":"value"}"""), "m")
        assertEquals("caller", result["prompt_cache_key"]?.jsonPrimitive?.content)
        assertEquals("channel-protocol", result["choice"]?.jsonPrimitive?.content)
        assertEquals("kept", result["patched"]?.jsonPrimitive?.content)
        assertEquals(json("""{"replacement":true}"""), result["metadata"])
        assertEquals("null", result["drop"].toString())
        assertFalse(result.containsKey(" !metadata "))
        val isolated = UpstreamRequestPolicy(global, UpstreamRequestOverrides(inheritGlobal = false), GatewayProtocol.RESPONSES, "bearer")
        assertFalse(isolated.body(json("{}"), "m").containsKey("prompt_cache_key"))
    }

    @Test
    fun `custom header placeholders match extra body without expanding caller text`() {
        val policy = UpstreamRequestPolicy(
            UpstreamRequestOverrides(),
            UpstreamRequestOverrides(
                headers = mapOf("X-Session" to "${'$'}{sessionId}/${'$'}{threadId}/${'$'}{subagentId}"),
                extraBody = json("""{"prompt_cache_key":"${'$'}{sessionId}"}"""),
            ),
            GatewayProtocol.RESPONSES, "bearer", conversation = UpstreamConversation("session", "thread", "agent"),
        )
        assertEquals("session/thread/agent", policy.resolvedHeaders()["x-session"])
        val original = json("""{"input":"literal ${'$'}{sessionId}"}""")
        val result = policy.body(original, "m")
        assertEquals(original["input"], result["input"])
        assertEquals("session", result["prompt_cache_key"]?.jsonPrimitive?.content)
    }

    @Test
    fun `rejects duplicate blank reserved and over deep extra body fields`() {
        val invalid = listOf(
            """{"":"x"}""", """{" ! ":"x"}""", """{"field":1,"!field":2}""", """{"field":1," field ":2}""",
        ) + listOf("model", "stream", "messages", "input", "contents", "system", "systemInstruction", "system_instruction", "instructions", "tools")
            .flatMap { listOf("""{"$it":null}""", """{" !$it ":null}""") }
        invalid.forEach { text ->
            val error = assertFailsWith<GatewayError> { validateUpstreamRequestOverrides(UpstreamRequestOverrides(extraBody = json(text))) }
            assertEquals("invalid_request_overrides", error.code)
        }
        val deep = "{\"nested\":".repeat(34) + "null" + "}".repeat(34)
        assertFailsWith<GatewayError> { validateUpstreamRequestOverrides(UpstreamRequestOverrides(extraBody = json(deep))) }
        val legacy = Json.decodeFromString<UpstreamRequestOverrides>("{}")
        assertTrue(legacy.extraBody.isEmpty())
        assertEquals(UpstreamRequestOverrides(), legacy)
    }

    @Test
    fun `extra body and expanded headers obey output size limits`() {
        val overrides = UpstreamRequestOverrides(extraBody = json("""{"value":"a very long generated value"}"""))
        val policy = UpstreamRequestPolicy(UpstreamRequestOverrides(), overrides, GatewayProtocol.RESPONSES, "bearer")
        assertFailsWith<GatewayError> { policy.body(json("{}"), "m", limitBytes = 24) }
        val headers = UpstreamRequestPolicy(
            UpstreamRequestOverrides(), UpstreamRequestOverrides(headers = mapOf("X-Test" to "${'$'}{sessionId}".repeat(65))),
            GatewayProtocol.RESPONSES, "bearer", conversation = UpstreamConversation("x".repeat(128)),
        )
        assertFailsWith<GatewayError> { headers.resolvedHeaders() }
    }

    @Test
    fun `a channel may opt out of global settings and presets`() {
        val builder = HttpRequestBuilder()
        val policy = UpstreamRequestPolicy(
            UpstreamRequestOverrides(userAgent = "global", headers = mapOf("X-Global" to "yes"), body = json("{\"temperature\":1}")),
            UpstreamRequestOverrides(inheritGlobal = false, headerProfile = "none"), GatewayProtocol.CHAT_COMPLETIONS, "bearer",
        )
        policy.applyHeaders(builder)
        assertNull(builder.headers["User-Agent"])
        assertNull(builder.headers["X-Global"])
        assertFalse(policy.body(json("{}"), "m").containsKey("temperature"))
    }

    @Test
    fun `Gemini patches keep model and stream in the URL rather than injecting them into JSON`() {
        val body = UpstreamRequestPolicy(
            UpstreamRequestOverrides(body = json("{\"generationConfig\":{\"temperature\":0.4}}")),
            UpstreamRequestOverrides(), GatewayProtocol.GEMINI, "google",
        ).body(json("{\"contents\":[]}"), "gemini-model")
        assertFalse(body.containsKey("model"))
        assertFalse(body.containsKey("stream"))
        assertTrue(body.containsKey("contents"))
    }

    @Test
    fun `rejects illegal headers managed credentials transport headers and protected body fields at save time`() {
        val invalid = listOf(
            UpstreamRequestOverrides(userAgent = "ua\r\nX-Injected: yes"),
            UpstreamRequestOverrides(headers = mapOf("Authorization" to "plaintext")),
            UpstreamRequestOverrides(headers = mapOf("x-goog-api-key" to "plaintext")),
            UpstreamRequestOverrides(headers = mapOf("Host" to "other-host")),
            UpstreamRequestOverrides(headers = mapOf("Content-Encoding" to "gzip")),
            UpstreamRequestOverrides(headers = mapOf("Content-Encoding" to null)),
            UpstreamRequestOverrides(headers = mapOf("Accept-Encoding" to "gzip")),
            UpstreamRequestOverrides(headers = mapOf("Accept-Encoding" to null)),
            UpstreamRequestOverrides(protocols = mapOf("gemini" to UpstreamProtocolOverrides(
                headers = mapOf("aCcEpT-EnCoDiNg" to "br"),
            ))),
            UpstreamRequestOverrides(protocols = mapOf("responses" to UpstreamProtocolOverrides(
                headers = mapOf("cOnTeNt-EnCoDiNg" to "br"),
            ))),
            UpstreamRequestOverrides(headers = mapOf("Bad Header" to "value")),
            UpstreamRequestOverrides(headers = mapOf("X-A" to "one", "x-a" to "two")),
            UpstreamRequestOverrides(body = json("{\"stream\":null}")),
            UpstreamRequestOverrides(protocols = mapOf("messages" to UpstreamProtocolOverrides(body = json("{\"model\":\"other\"}")))),
            UpstreamRequestOverrides(protocols = mapOf("unknown" to UpstreamProtocolOverrides())),
            UpstreamRequestOverrides(headerProfile = "unknown"),
        )
        invalid.forEach {
            assertEquals("invalid_request_overrides", assertFailsWith<GatewayError> { validateUpstreamRequestOverrides(it) }.code)
        }
    }

    @Test
    fun `rewritten UTF8 body still obeys the request size limit`() {
        val policy = UpstreamRequestPolicy(
            UpstreamRequestOverrides(body = json("{\"input\":\"你好你好你好\"}")),
            UpstreamRequestOverrides(), GatewayProtocol.RESPONSES, "bearer",
        )
        val error = assertFailsWith<GatewayError> { policy.body(json("{}"), "m", limitBytes = 25) }
        assertEquals(HttpStatusCode.PayloadTooLarge, error.status)
    }

    @Test
    fun `chat completion body removes max_tokens when max_completion_tokens override is configured`() {
        val convertedFromResponses = json("""{"model":"gpt","messages":[{"role":"user","content":"hi"}],"max_tokens":100}""")
        val convertedFromMessages = json("""{"model":"gpt","messages":[{"role":"user","content":"hi"}],"max_tokens":100}""")
        val convertedFromGemini = json("""{"model":"gpt","messages":[{"role":"user","content":"hi"}],"max_tokens":100}""")

        val compatiblePolicy = UpstreamRequestPolicy(
            UpstreamRequestOverrides(), UpstreamRequestOverrides(), GatewayProtocol.CHAT_COMPLETIONS, "bearer",
        )
        for (request in listOf(convertedFromResponses, convertedFromMessages, convertedFromGemini)) {
            val body = compatiblePolicy.body(request, "gpt-4o")
            assertEquals("100", body["max_tokens"]?.jsonPrimitive?.content)
            assertFalse(body.containsKey("max_completion_tokens"))
        }

        val reasoningPolicy = UpstreamRequestPolicy(
            UpstreamRequestOverrides(),
            UpstreamRequestOverrides(protocols = mapOf(
                "chat_completions" to UpstreamProtocolOverrides(body = json("""{"max_completion_tokens":200}""")),
            )),
            GatewayProtocol.CHAT_COMPLETIONS,
            "bearer",
        )
        for (request in listOf(convertedFromResponses, convertedFromMessages, convertedFromGemini)) {
            val body = reasoningPolicy.body(request, "o3-mini")
            assertEquals("200", body["max_completion_tokens"]?.jsonPrimitive?.content)
            assertFalse(body.containsKey("max_tokens"), "max_tokens should be stripped when max_completion_tokens is configured")
        }
    }

    @Test
    fun `only a positive numeric completion budget replaces the callers token limit`() {
        val original = json("""{"model":"m","messages":[{"role":"user","content":"hi"}],"max_tokens":7}""")
        for (value in listOf("null", "0", "-1", "\"32\"", "false", "{}", "[]")) {
            val policy = UpstreamRequestPolicy(
                UpstreamRequestOverrides(),
                UpstreamRequestOverrides(extraBody = json("""{"max_completion_tokens":$value}""")),
                GatewayProtocol.CHAT_COMPLETIONS, "bearer",
            )
            val body = policy.body(original, "m")
            assertEquals("7", body["max_tokens"]?.jsonPrimitive?.content, "invalid budget $value must not remove max_tokens")
            val limited = limitModelTestTokens(body, GatewayProtocol.CHAT_COMPLETIONS, 128)
            assertEquals("7", limited["max_tokens"]?.jsonPrimitive?.content, "administrative checks must retain the lower caller budget")
        }
        val policy = UpstreamRequestPolicy(
            UpstreamRequestOverrides(),
            UpstreamRequestOverrides(extraBody = json("""{"max_completion_tokens":32}""")),
            GatewayProtocol.CHAT_COMPLETIONS, "bearer",
        )
        val body = policy.body(original, "m")
        assertFalse(body.containsKey("max_tokens"))
        assertEquals("32", body["max_completion_tokens"]?.jsonPrimitive?.content)
        val limited = limitModelTestTokens(body, GatewayProtocol.CHAT_COMPLETIONS, 16)
        assertFalse(limited.containsKey("max_tokens"))
        assertEquals("16", limited["max_completion_tokens"]?.jsonPrimitive?.content)
    }

    @Test
    fun `chat completion body fills reasoning_content on tool turns only`() {
        val request = json("""
            {
                "model": "deepseek-flash",
                "messages": [
                    {"role": "user", "content": "hello"},
                    {"role": "assistant", "content": "hi"},
                    {"role": "assistant", "content": null, "tool_calls": [
                        {"id": "call_1", "type": "function", "function": {"name": "read", "arguments": "{}"}}
                    ]},
                    {"role": "tool", "tool_call_id": "call_1", "content": "done"},
                    {"role": "assistant", "content": "the file says hi"}
                ]
            }
        """.trimIndent())

        val policy = UpstreamRequestPolicy(
            UpstreamRequestOverrides(), UpstreamRequestOverrides(), GatewayProtocol.CHAT_COMPLETIONS, "bearer",
            baseUrl = "https://api.deepseek.com/v1",
        )
        val messages = policy.body(request, "deepseek-flash")["messages"] as kotlinx.serialization.json.JsonArray

        // 普通问答轮不注入；工具调用轮与工具结果之后的作答补单个空格。
        assertNull(messages[1].jsonObject["reasoning_content"])
        assertEquals(" ", messages[2].jsonObject["reasoning_content"]?.jsonPrimitive?.content)
        assertEquals(" ", messages[4].jsonObject["reasoning_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `chat completion body rewrites developer messages unconditionally`() {
        val request = json("""
            {
                "model": "m",
                "messages": [
                    {"role": "developer", "content": "policy"},
                    {"role": "user", "content": "hello"}
                ]
            }
        """.trimIndent())

        val roles = UpstreamRequestPolicy(
            UpstreamRequestOverrides(), UpstreamRequestOverrides(), GatewayProtocol.CHAT_COMPLETIONS, "bearer",
        ).body(request, "m")["messages"]!!.jsonArray.map { it.jsonObject["role"]?.jsonPrimitive?.content }

        assertEquals(listOf("system", "user"), roles)
    }

    @Test
    fun `responses keeps the developer role`() {
        val request = json("""
            {
                "model": "m",
                "input": [
                    {"type": "message", "role": "developer", "content": "policy"},
                    {"type": "message", "role": "user", "content": "hello"}
                ]
            }
        """.trimIndent())

        val roles = UpstreamRequestPolicy(
            UpstreamRequestOverrides(), UpstreamRequestOverrides(), GatewayProtocol.RESPONSES, "bearer",
        ).body(request, "m")["input"]!!.jsonArray.map { it.jsonObject["role"]?.jsonPrimitive?.content }

        assertEquals(listOf("developer", "user"), roles)
    }
}
