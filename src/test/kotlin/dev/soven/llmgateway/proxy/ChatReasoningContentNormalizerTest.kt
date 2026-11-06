package dev.soven.llmgateway.proxy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatReasoningContentNormalizerTest {
    private fun json(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun `auto fills empty reasoning_content when reasoning_effort is enabled`() {
        val input = json("""
            {
                "model": "deepseek-flash",
                "reasoning_effort": "max",
                "messages": [
                    {"role": "user", "content": "hello"},
                    {"role": "assistant", "content": "hi there"},
                    {"role": "user", "content": "continue"}
                ]
            }
        """.trimIndent())

        val normalized = ChatReasoningContentNormalizer.normalize(input, "deepseek-flash")
        val messages = normalized["messages"]!!.jsonArray

        assertEquals("hello", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertFalse(messages[0].jsonObject.containsKey("reasoning_content"))

        assertEquals("hi there", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("", messages[1].jsonObject["reasoning_content"]!!.jsonPrimitive.content)

        assertEquals("continue", messages[2].jsonObject["content"]!!.jsonPrimitive.content)
        assertFalse(messages[2].jsonObject.containsKey("reasoning_content"))
    }

    @Test
    fun `auto fills empty reasoning_content for deepseek model even without explicit reasoning_effort`() {
        val input = json("""
            {
                "model": "deepseek-v3",
                "messages": [
                    {"role": "user", "content": "test"},
                    {"role": "assistant", "content": "response"}
                ]
            }
        """.trimIndent())

        val normalized = ChatReasoningContentNormalizer.normalize(input, "deepseek-v3")
        val messages = normalized["messages"]!!.jsonArray

        assertEquals("", messages[1].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `auto fills empty reasoning_content for reasoner model`() {
        val input = json("""
            {
                "model": "custom-reasoner",
                "messages": [
                    {"role": "user", "content": "solve math"},
                    {"role": "assistant", "content": "answer"}
                ]
            }
        """.trimIndent())

        val normalized = ChatReasoningContentNormalizer.normalize(input, "custom-reasoner")
        val messages = normalized["messages"]!!.jsonArray

        assertEquals("", messages[1].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `preserves existing valid reasoning_content`() {
        val input = json("""
            {
                "model": "deepseek-r1",
                "messages": [
                    {"role": "user", "content": "hello"},
                    {"role": "assistant", "content": "hi", "reasoning_content": "my original thoughts"}
                ]
            }
        """.trimIndent())

        val normalized = ChatReasoningContentNormalizer.normalize(input, "deepseek-r1")
        val messages = normalized["messages"]!!.jsonArray

        assertEquals("my original thoughts", messages[1].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("hi", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `replaces null reasoning_content with empty string`() {
        val input = json("""
            {
                "model": "deepseek-flash",
                "reasoning_effort": "high",
                "messages": [
                    {"role": "user", "content": "hello"},
                    {"role": "assistant", "content": "hi", "reasoning_content": null}
                ]
            }
        """.trimIndent())

        val normalized = ChatReasoningContentNormalizer.normalize(input, "deepseek-flash")
        val messages = normalized["messages"]!!.jsonArray

        assertEquals("", messages[1].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `migrates reasoning alias field to reasoning_content`() {
        val input = json("""
            {
                "model": "deepseek-flash",
                "reasoning_effort": "medium",
                "messages": [
                    {"role": "user", "content": "hello"},
                    {"role": "assistant", "content": "hi", "reasoning": "step by step thoughts"}
                ]
            }
        """.trimIndent())

        val normalized = ChatReasoningContentNormalizer.normalize(input, "deepseek-flash")
        val messages = normalized["messages"]!!.jsonArray

        assertEquals("step by step thoughts", messages[1].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("hi", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `migrates thought alias field to reasoning_content`() {
        val input = json("""
            {
                "model": "deepseek-flash",
                "reasoning_effort": "low",
                "messages": [
                    {"role": "user", "content": "hello"},
                    {"role": "assistant", "content": "hi", "thought": "internal thoughts"}
                ]
            }
        """.trimIndent())

        val normalized = ChatReasoningContentNormalizer.normalize(input, "deepseek-flash")
        val messages = normalized["messages"]!!.jsonArray

        assertEquals("internal thoughts", messages[1].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `extracts thinking from think tag in content and cleans content`() {
        val input = json("""
            {
                "model": "deepseek-flash",
                "reasoning_effort": "max",
                "messages": [
                    {"role": "user", "content": "hello"},
                    {"role": "assistant", "content": "<think>\nThinking step by step\n</think>\nFinal answer"}
                ]
            }
        """.trimIndent())

        val normalized = ChatReasoningContentNormalizer.normalize(input, "deepseek-flash")
        val messages = normalized["messages"]!!.jsonArray

        assertEquals("Thinking step by step", messages[1].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("Final answer", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `extracts thinking from think tag when content only contains think tag`() {
        val input = json("""
            {
                "model": "deepseek-flash",
                "reasoning_effort": "max",
                "messages": [
                    {"role": "user", "content": "hello"},
                    {"role": "assistant", "content": "<think>Pure thought</think>"}
                ]
            }
        """.trimIndent())

        val normalized = ChatReasoningContentNormalizer.normalize(input, "deepseek-flash")
        val messages = normalized["messages"]!!.jsonArray

        assertEquals("Pure thought", messages[1].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `extracts thinking from multipart array content`() {
        val input = json("""
            {
                "model": "deepseek-flash",
                "reasoning_effort": "max",
                "messages": [
                    {"role": "user", "content": "hello"},
                    {
                        "role": "assistant",
                        "content": [
                            {"type": "text", "text": "<think>Plan steps</think>Result text"}
                        ]
                    }
                ]
            }
        """.trimIndent())

        val normalized = ChatReasoningContentNormalizer.normalize(input, "deepseek-flash")
        val messages = normalized["messages"]!!.jsonArray

        assertEquals("Plan steps", messages[1].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
        val parts = messages[1].jsonObject["content"]!!.jsonArray
        assertEquals("Result text", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `handles assistant message with tool_calls and null content`() {
        val input = json("""
            {
                "model": "deepseek-flash",
                "reasoning_effort": "max",
                "messages": [
                    {"role": "user", "content": "call a tool"},
                    {"role": "assistant", "content": null, "tool_calls": [{"id": "call_1", "type": "function"}]}
                ]
            }
        """.trimIndent())

        val normalized = ChatReasoningContentNormalizer.normalize(input, "deepseek-flash")
        val messages = normalized["messages"]!!.jsonArray

        assertEquals("", messages[1].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
        assertTrue(messages[1].jsonObject.containsKey("tool_calls"))
    }

    @Test
    fun `does not modify ordinary non-thinking non-deepseek models`() {
        val input = json("""
            {
                "model": "gpt-4o",
                "messages": [
                    {"role": "user", "content": "hello"},
                    {"role": "assistant", "content": "hi"}
                ]
            }
        """.trimIndent())

        val normalized = ChatReasoningContentNormalizer.normalize(input, "gpt-4o")
        val messages = normalized["messages"]!!.jsonArray

        assertFalse(messages[1].jsonObject.containsKey("reasoning_content"))
        assertEquals(input, normalized)
    }

    @Test
    fun `does not modify when reasoning_effort is explicitly set to none for non-reasoner model`() {
        val input = json("""
            {
                "model": "deepseek-chat",
                "reasoning_effort": "none",
                "messages": [
                    {"role": "user", "content": "hello"},
                    {"role": "assistant", "content": "hi"}
                ]
            }
        """.trimIndent())

        val normalized = ChatReasoningContentNormalizer.normalize(input, "deepseek-chat")
        val messages = normalized["messages"]!!.jsonArray

        assertFalse(messages[1].jsonObject.containsKey("reasoning_content"))
    }

    @Test
    fun `normalizes when reasoning_effort is none but model is deepseek-reasoner`() {
        val input = json("""
            {
                "model": "deepseek-reasoner",
                "reasoning_effort": "none",
                "messages": [
                    {"role": "user", "content": "hello"},
                    {"role": "assistant", "content": "hi"}
                ]
            }
        """.trimIndent())

        val normalized = ChatReasoningContentNormalizer.normalize(input, "deepseek-reasoner")
        val messages = normalized["messages"]!!.jsonArray

        assertEquals("", messages[1].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `normalizes non-deepseek model when history contains reasoning_content`() {
        val input = json("""
            {
                "model": "my-proxy-model",
                "messages": [
                    {"role": "user", "content": "hello"},
                    {"role": "assistant", "content": "first", "reasoning_content": "thought"},
                    {"role": "user", "content": "next"},
                    {"role": "assistant", "content": "second"}
                ]
            }
        """.trimIndent())

        val normalized = ChatReasoningContentNormalizer.normalize(input, "my-proxy-model")
        val messages = normalized["messages"]!!.jsonArray

        assertEquals("thought", messages[1].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("", messages[3].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `does not inject reasoning_content for reasoning_effort alone on a non-thinking model`() {
        // Groq 等上游接受 reasoning_effort，却会拒绝历史消息中的 reasoning_content。
        val input = json("""
            {
                "model": "openai/gpt-oss-120b",
                "reasoning_effort": "high",
                "messages": [
                    {"role": "user", "content": "hello"},
                    {"role": "assistant", "content": "hi"}
                ]
            }
        """.trimIndent())

        val normalized = ChatReasoningContentNormalizer.normalize(input, "openai/gpt-oss-120b")

        assertEquals(input, normalized)
        assertFalse(normalized["messages"]!!.jsonArray[1].jsonObject.containsKey("reasoning_content"))
    }

    @Test
    fun `never policy leaves thinking model requests untouched`() {
        val input = json("""
            {
                "model": "deepseek-flash",
                "reasoning_effort": "max",
                "messages": [
                    {"role": "user", "content": "hello"},
                    {"role": "assistant", "content": "hi"}
                ]
            }
        """.trimIndent())

        val normalized = ChatReasoningContentNormalizer.normalize(
            input, "deepseek-flash", ReasoningContentPolicy.NEVER,
        )

        assertEquals(input, normalized)
    }

    @Test
    fun `always policy injects for custom-named thinking proxies`() {
        val input = json("""
            {
                "model": "ds-proxy",
                "messages": [
                    {"role": "user", "content": "hello"},
                    {"role": "assistant", "content": "hi"}
                ]
            }
        """.trimIndent())

        val auto = ChatReasoningContentNormalizer.normalize(input, "ds-proxy", ReasoningContentPolicy.AUTO)
        assertFalse(auto["messages"]!!.jsonArray[1].jsonObject.containsKey("reasoning_content"))

        val always = ChatReasoningContentNormalizer.normalize(input, "ds-proxy", ReasoningContentPolicy.ALWAYS)
        assertEquals("", always["messages"]!!.jsonArray[1].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
    }
}
