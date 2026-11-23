package dev.soven.prismllm.proxy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@Tag("unit")
class UpstreamConversationTest {
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test
    fun `uses native session and Codex thread metadata with non recursive expansion`() {
        val headers = mapOf(
            "session_id" to "session-1",
            "x-codex-turn-metadata" to """{"thread_id":"thread-2"}""",
            "x-claude-code-agent-id" to "agent-3",
        )
        val c = UpstreamConversation.fromRequest(headers::get, json("{}"), "caller-1")
        assertEquals("session-1/thread-2/agent-3", c.expand("\${sessionId}/\${threadId}/\${subagentId}"))
        assertEquals("\${threadId}/original-thread", UpstreamConversation("\${threadId}", "original-thread")
            .expand("\${sessionId}/\${threadId}"))
    }

    @Test
    fun `thread falls back to session and absent subagent is empty`() {
        val c = UpstreamConversation.fromRequest({ if (it == "x-session-id") "s1" else null }, json("{}"), "caller")
        assertEquals("s1/s1/", c.expand("\${sessionId}/\${threadId}/\${subagentId}"))
        val body = json("""{"client_metadata":{"x-codex-turn-metadata":"{\"thread_id\":\"body-thread\"}"}}""")
        assertEquals("body-thread", UpstreamConversation.fromRequest({ null }, body, "caller").threadId)
    }

    @Test
    fun `headerless conversation is stable across turns and isolated by caller and opening`() {
        for ((first, second) in listOf(
            """{"messages":[{"role":"system","content":"rules"},{"role":"user","content":"hello"}]}""" to
                """{"messages":[{"role":"system","content":"rules"},{"role":"user","content":"hello"},{"role":"assistant","content":"hi"},{"role":"user","content":"next"}]}""",
            """{"input":[{"role":"user","content":[{"type":"input_text","text":"hello"}]}]}""" to
                """{"input":[{"role":"user","content":[{"type":"input_text","text":"hello"}]},{"role":"assistant","content":"hi"},{"role":"user","content":"next"}]}""",
            """{"contents":[{"parts":[{"text":"hello"}]}]}""" to
                """{"contents":[{"parts":[{"text":"hello"}]},{"role":"model","parts":[{"text":"hi"}]}]}""",
        )) {
            val c = UpstreamConversation.fromRequest({ null }, json(first), "caller-1")
            assertEquals(c.sessionId, UpstreamConversation.fromRequest({ null }, json(second), "caller-1").sessionId)
            assertNotEquals(c.sessionId, UpstreamConversation.fromRequest({ null }, json(first), "caller-2").sessionId)
            assertFalse(c.sessionId.contains("hello"))
        }
        val a = UpstreamConversation.fromRequest({ null }, json("""{"input":"hello"}"""), "caller")
        val b = UpstreamConversation.fromRequest({ null }, json("""{"input":"different"}"""), "caller")
        assertNotEquals(a.sessionId, b.sessionId)
    }

    @Test
    fun `headerless text input keeps its identity when represented as message history`() {
        val inputs = listOf(
            """{"input":"hello"}""",
            """{"input":[{"role":"user","content":"hello"},{"role":"assistant","content":"hi"},{"role":"user","content":"next"}]}""",
            """{"input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"hello"}]},{"role":"assistant","content":"hi"}]}""",
            """{"messages":[{"role":"system","content":"rules"},{"role":"user","content":"hello"}]}""",
            """{"messages":[{"role":"user","content":[{"type":"text","text":"hello"}]}]}""",
            """{"contents":[{"parts":[{"text":"hello"}]}]}""",
            """{"contents":[{"role":"user","parts":[{"text":"hello"}]},{"role":"model","parts":[{"text":"hi"}]}]}""",
        )
        val expected = UpstreamConversation.fromRequest({ null }, json(inputs.first()), "caller")
        inputs.forEach { input ->
            val actual = UpstreamConversation.fromRequest({ null }, json(input), "caller")
            assertEquals(expected, actual, input)
            assertNotEquals(expected.sessionId, UpstreamConversation.fromRequest({ null }, json(input), "other-caller").sessionId)
        }
        assertNotEquals(expected.sessionId, UpstreamConversation.fromRequest({ null }, json("""{"input":"other text"}"""), "caller").sessionId)
    }

    @Test
    fun `normalizing the opening preserves multimodal content and ignores JSON member ordering`() {
        val first = json("""{"input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"hello"},{"type":"input_image","image_url":"https://example.invalid/first.png"}]}]}""")
        val reordered = json("""{"input":[{"role":"user","content":[{"text":"hello","type":"input_text"},{"image_url":"https://example.invalid/first.png","type":"input_image"}]}]}""")
        val otherImage = json("""{"input":[{"role":"user","content":[{"type":"input_text","text":"hello"},{"type":"input_image","image_url":"https://example.invalid/other.png"}]}]}""")
        fun session(body: kotlinx.serialization.json.JsonObject) = UpstreamConversation.fromRequest({ null }, body, "caller").sessionId
        assertEquals(session(first), session(reordered))
        assertNotEquals(session(first), session(otherImage))
        assertNotEquals(session(first), session(json("""{"input":"hello"}""")))
        assertEquals("native", UpstreamConversation.fromRequest({ if (it == "session_id") "native" else null }, first, "caller").sessionId)
    }

    @Test
    fun `headers remove unsafe identity bytes and own test sessions differ`() {
        val c = UpstreamConversation("s\r\n\t\u007f你")
        assertEquals("s", c.expand("\${sessionId}", header = true))
        assertEquals(c.sessionId, c.expand("\${sessionId}"))
        val first = UpstreamConversation.own("prismllm-test-")
        assertTrue(first.sessionId.startsWith("prismllm-test-"))
        assertNotEquals(first.sessionId, UpstreamConversation.own("prismllm-test-").sessionId)
    }
}
