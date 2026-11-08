package dev.soven.llmgateway.proxy.codeassist

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Code Assist 信封与 Antigravity 提示词兼容层的回归测试。 */
class CodeAssistEnvelopeTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun body(): JsonObject = json.parseToJsonElement(
        """{"contents":[{"role":"user","parts":[{"text":"hello"}]}],"generationConfig":{"temperature":0.2}}""",
    ).jsonObject

    @Test
    fun wrapCarriesModelProjectAndStableSession() {
        val source = body()
        val wrapped = CodeAssistEnvelope.wrap(source, "gemini-3-pro", "project-1")

        assertEquals("gemini-3-pro", wrapped["model"]!!.jsonPrimitive.content)
        assertEquals("project-1", wrapped["project"]!!.jsonPrimitive.content)
        assertEquals("antigravity", wrapped["userAgent"]!!.jsonPrimitive.content)
        assertEquals("agent", wrapped["requestType"]!!.jsonPrimitive.content)
        val request = wrapped["request"]!!.jsonObject
        assertFalse("model" in request)
        assertEquals(CodeAssistEnvelope.sessionId(source), request["sessionId"]!!.jsonPrimitive.content)
    }

    @Test
    fun sessionIdFollowsTheFirstUserText() {
        val first = json.parseToJsonElement(
            """{"contents":[{"role":"user","parts":[{"text":"same question"}]}]}""",
        ).jsonObject
        val second = json.parseToJsonElement(
            """{"contents":[{"role":"user","parts":[{"text":"same question"}]},{"role":"model","parts":[{"text":"answer"}]}]}""",
        ).jsonObject
        assertEquals(CodeAssistEnvelope.sessionId(first), CodeAssistEnvelope.sessionId(second))
        assertTrue(CodeAssistEnvelope.sessionId(first).startsWith("-"))
    }

    @Test
    fun sessionIdSeparatesSessionsWithTheSameFirstMessage() {
        val request = json.parseToJsonElement(
            """{"contents":[{"role":"user","parts":[{"text":"hello"}]}]}""",
        ).jsonObject
        val left = CodeAssistEnvelope.sessionId(
            request,
            CodeAssistEnvelope.sessionScope("alias-a", "channel-1", "req-1", request),
        )
        val otherAlias = CodeAssistEnvelope.sessionId(
            request,
            CodeAssistEnvelope.sessionScope("alias-b", "channel-1", "req-1", request),
        )
        val otherChannel = CodeAssistEnvelope.sessionId(
            request,
            CodeAssistEnvelope.sessionScope("alias-a", "channel-2", "req-1", request),
        )
        val otherRequest = CodeAssistEnvelope.sessionId(
            request,
            CodeAssistEnvelope.sessionScope("alias-a", "channel-1", "req-2", request),
        )
        // 同一个会话内请求标识逐次不同：相同首条消息的不同再次请求也不能落到同一个上游会话。
        assertEquals(
            left,
            CodeAssistEnvelope.sessionId(request, CodeAssistEnvelope.sessionScope("alias-a", "channel-1", "req-1", request)),
        )
        assertTrue(left != otherAlias)
        assertTrue(left != otherChannel)
        assertTrue(left != otherRequest)
    }

    @Test
    fun errorBodyKeepsOnlyTheInnerError() {
        assertEquals(
            """{"error":{"code":429,"message":"Resource has been exhausted"}}""",
            CodeAssistEnvelope.errorBody("""{"error":{"code":429,"message":"Resource has been exhausted"}}"""),
        )
        // 信封里带 response 占位时也只取 error。
        assertEquals("""{"error":{"code":503}}""", CodeAssistEnvelope.errorBody("""{"response":null,"error":{"code":503}}"""))
        // 不是 JSON 或没有 error 字段时原样返回，交给下游按原格式处理。
        assertEquals("<html>bad gateway</html>", CodeAssistEnvelope.errorBody("<html>bad gateway</html>"))
        assertEquals("""{"message":"plain"}""", CodeAssistEnvelope.errorBody("""{"message":"plain"}"""))
    }

    @Test
    fun unwrapTakesTheResponseField() {
        val parsed = json.parseToJsonElement("""{"response":{"candidates":[]}}""").jsonObject
        assertEquals("""{"candidates":[]}""", CodeAssistEnvelope.unwrap(parsed).toString())
        val bare = json.parseToJsonElement("""{"candidates":[]}""").jsonObject
        assertEquals(bare, CodeAssistEnvelope.unwrap(bare))
    }

    @Test
    fun sseLinesAreUnwrappedButOthersLeftAlone() {
        assertEquals(
            """data: {"candidates":[]}""",
            CodeAssistEnvelope.unwrapSseLine("""data: {"response":{"candidates":[]}}"""),
        )
        assertEquals("data: [DONE]", CodeAssistEnvelope.unwrapSseLine("data: [DONE]"))
        assertEquals("", CodeAssistEnvelope.unwrapSseLine(""))
    }

    @Test
    fun cloakBreaksTheLinesAntigravityTurnsAway() {
        val prompt = "You are Codex, an agent based on GPT-5. Follow the rules."
        val cloaked = CodeAssistCloak.cloak(prompt)
        assertTrue(cloaked.contains("Y" + ZW + "ou are Codex, an agent based on GPT-5"))
        assertEquals(prompt.length + ZW.length, cloaked.length)
    }

    @Test
    fun cloakLeavesOtherPromptsAsWritten() {
        val prompt = "You are a careful assistant."
        assertEquals(prompt, CodeAssistCloak.cloak(prompt))
        assertFalse(CodeAssistCloak.turnsAway(prompt))
    }

    @Test
    fun applyOnlyRewritesTheSystemInstruction() {
        val source = json.parseToJsonElement(
            """{"systemInstruction":{"parts":[{"text":"You are Codex, an agent based on GPT-5."}]},"contents":[{"role":"user","parts":[{"text":"hi"}]}]}""",
        ).jsonObject
        val applied = CodeAssistCloak.apply(source)
        val parts = applied["systemInstruction"]!!.jsonObject["parts"] as JsonArray
        val text = (parts[0] as JsonObject)["text"]!!.jsonPrimitive.content
        assertTrue(text.contains(ZW))
        assertEquals(source["contents"], applied["contents"])
    }

    private companion object {
        const val ZW = "\u200b"
    }
}
