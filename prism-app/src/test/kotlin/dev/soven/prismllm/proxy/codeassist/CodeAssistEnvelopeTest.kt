package dev.soven.prismllm.proxy.codeassist

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Code Assist 信封与 Antigravity 提示词兼容层的回归测试。 */
@Tag("unit")
class CodeAssistEnvelopeTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun body(): JsonObject = json.parseToJsonElement(
        """{"contents":[{"role":"user","parts":[{"text":"hello"}]}],"generationConfig":{"temperature":0.2}}""",
    ).jsonObject

    @Test
    fun wrapCarriesModelProjectAndStableSession() {
        val scope = CodeAssistEnvelope.sessionScope("alias-a", "channel-1", "key-1", "session-1")
        val wrapped = CodeAssistEnvelope.wrap(body(), "gemini-3-pro", "project-1", scope)

        assertEquals("gemini-3-pro", wrapped["model"]!!.jsonPrimitive.content)
        assertEquals("project-1", wrapped["project"]!!.jsonPrimitive.content)
        assertEquals("antigravity", wrapped["userAgent"]!!.jsonPrimitive.content)
        assertEquals("agent", wrapped["requestType"]!!.jsonPrimitive.content)
        assertTrue(
            wrapped["requestId"]!!.jsonPrimitive.content.matches(
                Regex("""agent/[0-9a-f-]{36}/\d+/[0-9a-f-]{36}/1"""),
            ),
        )
        val request = wrapped["request"]!!.jsonObject
        assertFalse("model" in request)
        // 同一会话的每一轮都是同一个 sessionId，客户端传来的正文不参与派生。
        assertEquals(CodeAssistEnvelope.sessionId(scope), request["sessionId"]!!.jsonPrimitive.content)
        val labels = request["labels"]!!.jsonObject
        assertEquals(labels["cascade_id"], labels["root_cascade_id"])
        assertEquals("0", labels["last_step_index"]!!.jsonPrimitive.content)
        assertEquals(
            labels["trajectory_id"]!!.jsonPrimitive.content + "-0",
            labels["request_id"]!!.jsonPrimitive.content,
        )
        assertEquals("false", labels["used_claude"]!!.jsonPrimitive.content)
        assertEquals("false", labels["used_non_gemini_model"]!!.jsonPrimitive.content)
    }

    @Test
    fun generationDefaultsFollowSelectedEffort() {
        val low = CodeAssistEnvelope.wrap(
            body(), "gemini-3.5-flash-lite", "project-1", "scope",
            effort = "low", maxOutputTokens = 65535,
        )
        val lowConfig = low["request"]!!.jsonObject["generationConfig"]!!.jsonObject
        assertEquals(65535, lowConfig["maxOutputTokens"]!!.jsonPrimitive.int)
        assertEquals("LOW", lowConfig["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content)
        assertEquals(true, lowConfig["thinkingConfig"]!!.jsonObject["includeThoughts"]!!.jsonPrimitive.content.toBoolean())

        val high = CodeAssistEnvelope.wrap(
            body(), "gemini-4-hydrogen-tiered-high", "project-1", "scope",
            effort = "high", maxOutputTokens = 262144,
        )
        val highConfig = high["request"]!!.jsonObject["generationConfig"]!!.jsonObject
        assertEquals(262144, highConfig["maxOutputTokens"]!!.jsonPrimitive.int)
        assertEquals("HIGH", highConfig["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content)

        val xhigh = CodeAssistEnvelope.wrap(
            body(), "gemini-4-hydrogen-tiered-xhigh", "project-1", "scope",
            effort = "xhigh", maxOutputTokens = 262144,
        )
        val xhighConfig = xhigh["request"]!!.jsonObject["generationConfig"]!!.jsonObject
        assertEquals(100, xhighConfig["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.int)
    }

    @Test
    fun requestIdUsesProvidedClock() {
        val wrapped = CodeAssistEnvelope.wrap(
            body(), "gemini-3-pro", "project-1", "scope", now = { 1_700_000_000_000 },
        )
        assertTrue(
            wrapped["requestId"]!!.jsonPrimitive.content.matches(
                Regex("""agent/[0-9a-f-]{36}/1700000000000/[0-9a-f-]{36}/1"""),
            ),
        )
    }

    @Test
    fun thinkingBudgetIsNotOverriddenByThinkingLevel() {
        val input = json.parseToJsonElement(
            """{"contents":[{"role":"user","parts":[{"text":"hi"}]}],"generationConfig":{"thinkingConfig":{"thinkingBudget":1024,"includeThoughts":true}}}""",
        ).jsonObject
        val wrapped = CodeAssistEnvelope.wrap(input, "gemini-4-hydrogen-tiered-high", "project-1", "scope", effort = "high")
        val thinking = wrapped["request"]!!.jsonObject["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
        assertEquals(1024, thinking["thinkingBudget"]!!.jsonPrimitive.int)
        assertFalse("thinkingLevel" in thinking)
    }

    @Test
    fun unknownEffortDoesNotForceALevel() {
        val wrapped = CodeAssistEnvelope.wrap(body(), "gemini-3-pro", "project-1", "scope")
        val thinking = wrapped["request"]!!.jsonObject["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
        assertFalse("thinkingLevel" in thinking)
    }

    @Test
    fun systemInstructionGetsUserRole() {
        val input = json.parseToJsonElement(
            """{"systemInstruction":{"parts":[{"text":"system"}]},"contents":[{"role":"user","parts":[{"text":"hi"}]}]}""",
        ).jsonObject
        val wrapped = CodeAssistEnvelope.wrap(input, "gemini-3-pro", "project-1", "scope")
        val system = wrapped["request"]!!.jsonObject["systemInstruction"]!!.jsonObject
        assertEquals("user", system["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun claudeModelIsMarkedInLabels() {
        val wrapped = CodeAssistEnvelope.wrap(body(), "claude-opus-5-5-high", "project-1", "scope", effort = "high")
        val labels = wrapped["request"]!!.jsonObject["labels"]!!.jsonObject
        assertEquals("true", labels["used_claude"]!!.jsonPrimitive.content)
        assertEquals("true", labels["used_non_gemini_model"]!!.jsonPrimitive.content)
    }

    @Test
    fun sessionIdIsStableForOneScopeAndDiffersAcrossScopes() {
        val scope = CodeAssistEnvelope.sessionScope("alias-a", "channel-1", "key-1", "session-1")
        // 同一次会话（含同一段对话的多轮）派生同一个上游会话 id。
        assertEquals(CodeAssistEnvelope.sessionId(scope), CodeAssistEnvelope.sessionId(scope))
        assertTrue(CodeAssistEnvelope.sessionId(scope).startsWith("-"))

        // 别名、渠道、凭据、调用方会话标识任一不同，都不是同一个会话。
        val others = listOf(
            CodeAssistEnvelope.sessionScope("alias-b", "channel-1", "key-1", "session-1"),
            CodeAssistEnvelope.sessionScope("alias-a", "channel-2", "key-1", "session-1"),
            CodeAssistEnvelope.sessionScope("alias-a", "channel-1", "key-2", "session-1"),
            CodeAssistEnvelope.sessionScope("alias-a", "channel-1", "key-1", "session-2"),
        )
        others.forEach { other -> assertTrue(CodeAssistEnvelope.sessionId(scope) != CodeAssistEnvelope.sessionId(other)) }
    }

    @Test
    fun sessionIdWithoutScopeNeverReusesASession() {
        // 调用方没有会话标识时无法区分两段对话，每次请求都用新的会话，不与他人共用。
        val first = CodeAssistEnvelope.sessionId(null)
        assertTrue(first.startsWith("-"))
        assertTrue(first != CodeAssistEnvelope.sessionId(null))
        assertTrue(first != CodeAssistEnvelope.sessionId(""))
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
