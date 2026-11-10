package dev.soven.prismllm.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatReasoningContentNormalizerTest {
    private fun json(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private fun toolTurnBody(model: String = "deepseek-flash") = json(
        """
        {
            "model": "$model",
            "messages": [
                {"role": "user", "content": "read the file"},
                {"role": "assistant", "content": null, "tool_calls": [
                    {"id": "call_1", "type": "function", "function": {"name": "read", "arguments": "{}"}}
                ]},
                {"role": "tool", "tool_call_id": "call_1", "content": "done"},
                {"role": "assistant", "content": "the file says hi"}
            ]
        }
        """.trimIndent(),
    )

    @Test
    fun `fills a single space on a tool call turn`() {
        val normalized = ChatReasoningContentNormalizer.normalize(
            toolTurnBody(), "deepseek-flash", "https://api.deepseek.com/v1",
        )
        val messages = normalized["messages"]!!.jsonArray

        assertEquals(" ", messages[1].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
        assertFalse(messages[0].jsonObject.containsKey("reasoning_content"))
    }

    @Test
    fun `fills the answer that follows a tool result`() {
        val normalized = ChatReasoningContentNormalizer.normalize(
            toolTurnBody(), "deepseek-flash", "https://api.deepseek.com/v1",
        )
        val messages = normalized["messages"]!!.jsonArray

        assertEquals(" ", messages[3].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("the file says hi", messages[3].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `leaves plain assistant turns untouched`() {
        val input = json(
            """
            {
                "model": "deepseek-flash",
                "messages": [
                    {"role": "user", "content": "hello"},
                    {"role": "assistant", "content": "hi there"},
                    {"role": "user", "content": "continue"},
                    {"role": "assistant", "content": "and more"}
                ]
            }
            """.trimIndent(),
        )
        val normalized = ChatReasoningContentNormalizer.normalize(
            input, "deepseek-flash", "https://api.deepseek.com/v1",
        )

        assertEquals(input, normalized)
    }

    @Test
    fun `a user turn ends the tool chain`() {
        val input = json(
            """
            {
                "model": "deepseek-flash",
                "messages": [
                    {"role": "tool", "tool_call_id": "call_1", "content": "done"},
                    {"role": "user", "content": "thanks, now something else"},
                    {"role": "assistant", "content": "sure"}
                ]
            }
            """.trimIndent(),
        )
        val normalized = ChatReasoningContentNormalizer.normalize(
            input, "deepseek-flash", "https://api.deepseek.com/v1",
        )

        assertEquals(input, normalized)
    }

    @Test
    fun `the tool turn ends after the answer that follows a tool result`() {
        // 工具结果之后的第一条 assistant 才是那一轮的回答；再往后的普通轮次不该被补字段。
        val input = json(
            """
            {
                "model": "deepseek-flash",
                "messages": [
                    {"role": "tool", "tool_call_id": "call_1", "content": "done"},
                    {"role": "assistant", "content": "the file says hi"},
                    {"role": "assistant", "content": "one more thing"}
                ]
            }
            """.trimIndent(),
        )
        val normalized = ChatReasoningContentNormalizer.normalize(
            input, "deepseek-flash", "https://api.deepseek.com/v1",
        )
        val messages = normalized["messages"]!!.jsonArray

        assertEquals(" ", messages[1].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
        assertFalse(messages[2].jsonObject.containsKey("reasoning_content"))
    }

    @Test
    fun `drops reasoning when the message already carries reasoning_content`() {
        // pi 会把思考内容同时放进 reasoning_content 与 reasoning；自建 DeepSeek 读成一个字段，
        // 会以 "duplicate field reasoning" 拒绝整条请求。
        val input = json(
            """
            {
                "model": "deepseek-flash",
                "messages": [
                    {"role": "assistant", "content": null, "reasoning_content": "keep me",
                     "reasoning": "keep me", "tool_calls": [
                        {"id": "call_1", "type": "function", "function": {"name": "read", "arguments": "{}"}}
                    ]}
                ]
            }
            """.trimIndent(),
        )
        val normalized = ChatReasoningContentNormalizer.normalize(input, "deepseek-flash", "https://api.deepseek.com")
        val message = normalized["messages"]!!.jsonArray[0].jsonObject

        assertEquals("keep me", message["reasoning_content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an empty reasoning_content also drops the reasoning alias`() {
        val input = json(
            """
            {
                "model": "deepseek-flash",
                "messages": [
                    {"role": "assistant", "content": null, "reasoning_content": "", "reasoning": "x",
                     "tool_calls": [
                        {"id": "call_1", "type": "function", "function": {"name": "read", "arguments": "{}"}}
                    ]}
                ]
            }
            """.trimIndent(),
        )
        val normalized = ChatReasoningContentNormalizer.normalize(input, "deepseek-flash", "https://api.deepseek.com")
        val message = normalized["messages"]!!.jsonArray[0].jsonObject

        assertEquals("", message["reasoning_content"]!!.jsonPrimitive.content)
        assertFalse(message.containsKey("reasoning"))
    }

    @Test
    fun `a plain model name r1 triggers on a relay host`() {
        val input = json(
            """
            {
                "model": "r1",
                "messages": [
                    {"role": "assistant", "content": null, "tool_calls": [
                        {"id": "call_1", "type": "function", "function": {"name": "read", "arguments": "{}"}}
                    ]}
                ]
            }
            """.trimIndent(),
        )
        val normalized = ChatReasoningContentNormalizer.normalize(input, "r1", "https://api.bblabu.ai/v1")

        assertEquals(
            " ",
            normalized["messages"]!!.jsonArray[0].jsonObject["reasoning_content"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `deepseek host triggers even when the model name says nothing`() {
        val input = json(
            """
            {
                "model": "v4.1-flash",
                "messages": [
                    {"role": "assistant", "content": null, "tool_calls": [
                        {"id": "call_1", "type": "function", "function": {"name": "read", "arguments": "{}"}}
                    ]}
                ]
            }
            """.trimIndent(),
        )
        val normalized = ChatReasoningContentNormalizer.normalize(
            input, "v4.1-flash", "https://api.deepseek.com/v1",
        )

        assertEquals(
            " ",
            normalized["messages"]!!.jsonArray[0].jsonObject["reasoning_content"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `deepseek model behind a relay triggers on the model name`() {
        val input = json(
            """
            {
                "model": "deepseek-v4.1-flash-1017",
                "messages": [
                    {"role": "assistant", "content": null, "tool_calls": [
                        {"id": "call_1", "type": "function", "function": {"name": "read", "arguments": "{}"}}
                    ]}
                ]
            }
            """.trimIndent(),
        )
        val normalized = ChatReasoningContentNormalizer.normalize(
            input, "deepseek-v4.1-flash-1017", "https://api.a6api.vip/v1",
        )

        assertTrue(normalized["messages"]!!.jsonArray[0].jsonObject.containsKey("reasoning_content"))
    }

    @Test
    fun `relay without a recognizable model name is left alone`() {
        val input = json(
            """
            {
                "model": "gpt-4o",
                "messages": [
                    {"role": "assistant", "content": null, "tool_calls": [
                        {"id": "call_1", "type": "function", "function": {"name": "read", "arguments": "{}"}}
                    ]}
                ]
            }
            """.trimIndent(),
        )
        val normalized = ChatReasoningContentNormalizer.normalize(
            input, "gpt-4o", "https://api.bblabu.ai/v1",
        )

        assertEquals(input, normalized)
    }

    @Test
    fun `mistral is never touched`() {
        val input = toolTurnBody("mistral-large")
        val normalized = ChatReasoningContentNormalizer.normalize(
            input, "deepseek-flash", "https://api.mistral.ai/v1",
        )

        assertEquals(input, normalized)
    }

    @Test
    fun `keeps an existing reasoning_content, including an empty one`() {
        val input = json(
            """
            {
                "model": "deepseek-r1",
                "messages": [
                    {"role": "assistant", "content": null, "reasoning_content": "my thoughts",
                     "tool_calls": [{"id": "call_1", "type": "function", "function": {"name": "read", "arguments": "{}"}}]},
                    {"role": "tool", "tool_call_id": "call_1", "content": "done"},
                    {"role": "assistant", "content": "ok", "reasoning_content": ""}
                ]
            }
            """.trimIndent(),
        )
        val normalized = ChatReasoningContentNormalizer.normalize(input, "deepseek-r1", "https://api.deepseek.com")
        val messages = normalized["messages"]!!.jsonArray

        assertEquals("my thoughts", messages[0].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("", messages[2].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a null reasoning_content is refilled`() {
        val input = json(
            """
            {
                "model": "deepseek-flash",
                "messages": [
                    {"role": "assistant", "content": null, "reasoning_content": null, "tool_calls": [
                        {"id": "call_1", "type": "function", "function": {"name": "read", "arguments": "{}"}}
                    ]}
                ]
            }
            """.trimIndent(),
        )
        val normalized = ChatReasoningContentNormalizer.normalize(input, "deepseek-flash", "https://api.deepseek.com")

        assertEquals(
            " ",
            normalized["messages"]!!.jsonArray[0].jsonObject["reasoning_content"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `moves reasoning into reasoning_content and drops the alias`() {
        val input = json(
            """
            {
                "model": "deepseek-flash",
                "messages": [
                    {"role": "assistant", "content": null, "reasoning": "from another client", "tool_calls": [
                        {"id": "call_1", "type": "function", "function": {"name": "read", "arguments": "{}"}}
                    ]}
                ]
            }
            """.trimIndent(),
        )
        val normalized = ChatReasoningContentNormalizer.normalize(input, "deepseek-flash", "https://api.deepseek.com")
        val message = normalized["messages"]!!.jsonArray[0].jsonObject

        assertEquals("from another client", message["reasoning_content"]!!.jsonPrimitive.content)
        // 把两个字段当同一个读的自建 DeepSeek 会以 duplicate field reasoning 拒绝。
        assertNull(message["reasoning"])
    }

    @Test
    fun `moves the thought alias too`() {
        val input = json(
            """
            {
                "model": "deepseek-flash",
                "messages": [
                    {"role": "assistant", "content": null, "thought": "internal", "tool_calls": [
                        {"id": "call_1", "type": "function", "function": {"name": "read", "arguments": "{}"}}
                    ]}
                ]
            }
            """.trimIndent(),
        )
        val normalized = ChatReasoningContentNormalizer.normalize(input, "deepseek-flash", "https://api.deepseek.com")
        val message = normalized["messages"]!!.jsonArray[0].jsonObject

        assertEquals("internal", message["reasoning_content"]!!.jsonPrimitive.content)
        // thought 不作为同义字段删除，只有 reasoning 会被自建 DeepSeek 当成重复字段。
        assertTrue(message.containsKey("thought"))
    }

    @Test
    fun `joins reasoning_details text and summary`() {
        val input = json(
            """
            {
                "model": "deepseek-flash",
                "messages": [
                    {"role": "assistant", "content": null, "reasoning_details": [
                        {"type": "reasoning.text", "text": "first"},
                        {"type": "reasoning.summary", "summary": " second"}
                    ], "tool_calls": [
                        {"id": "call_1", "type": "function", "function": {"name": "read", "arguments": "{}"}}
                    ]}
                ]
            }
            """.trimIndent(),
        )
        val normalized = ChatReasoningContentNormalizer.normalize(input, "deepseek-flash", "https://api.deepseek.com")
        val message = normalized["messages"]!!.jsonArray[0].jsonObject

        assertEquals("first second", message["reasoning_content"]!!.jsonPrimitive.content)
        // reasoning_details 是结构化列表，原样保留。
        assertTrue(message.containsKey("reasoning_details"))
    }

    @Test
    fun `extracts think tags out of a string content`() {
        val input = json(
            """
            {
                "model": "deepseek-flash",
                "messages": [
                    {"role": "assistant", "content": "<think>step one</think>\n\nthe answer", "tool_calls": [
                        {"id": "call_1", "type": "function", "function": {"name": "read", "arguments": "{}"}}
                    ]}
                ]
            }
            """.trimIndent(),
        )
        val normalized = ChatReasoningContentNormalizer.normalize(input, "deepseek-flash", "https://api.deepseek.com")
        val message = normalized["messages"]!!.jsonArray[0].jsonObject

        assertEquals("step one", message["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("the answer", message["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an empty think tag still falls back to a single space`() {
        // Qwen3 非思考模式会输出 <think></think>；标签要剔除，但不能写出空串。
        val input = json(
            """
            {
                "model": "deepseek-flash",
                "messages": [
                    {"role": "assistant", "content": "<think>\n\n</think>the answer", "tool_calls": [
                        {"id": "call_1", "type": "function", "function": {"name": "read", "arguments": "{}"}}
                    ]}
                ]
            }
            """.trimIndent(),
        )
        val normalized = ChatReasoningContentNormalizer.normalize(input, "deepseek-flash", "https://api.deepseek.com")
        val message = normalized["messages"]!!.jsonArray[0].jsonObject

        assertEquals(" ", message["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("the answer", message["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an empty think tag in content parts is stripped and filled with a single space`() {
        val input = json(
            """
            {
                "model": "deepseek-flash",
                "messages": [
                    {"role": "assistant", "content": [
                        {"type": "text", "text": "<think></think>the answer"}
                    ], "tool_calls": [
                        {"id": "call_1", "type": "function", "function": {"name": "read", "arguments": "{}"}}
                    ]}
                ]
            }
            """.trimIndent(),
        )
        val normalized = ChatReasoningContentNormalizer.normalize(input, "deepseek-flash", "https://api.deepseek.com")
        val message = normalized["messages"]!!.jsonArray[0].jsonObject

        assertEquals(" ", message["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals(
            "the answer",
            message["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `content parts without think tags are left untouched`() {
        val input = json(
            """
            {
                "model": "deepseek-flash",
                "messages": [
                    {"role": "assistant", "content": [
                        {"type": "text", "text": "plain answer"}
                    ], "tool_calls": [
                        {"id": "call_1", "type": "function", "function": {"name": "read", "arguments": "{}"}}
                    ]}
                ]
            }
            """.trimIndent(),
        )
        val normalized = ChatReasoningContentNormalizer.normalize(input, "deepseek-flash", "https://api.deepseek.com")
        val message = normalized["messages"]!!.jsonArray[0].jsonObject

        assertEquals(" ", message["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals(
            "plain answer",
            message["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `extracts think tags out of content parts`() {
        val input = json(
            """
            {
                "model": "deepseek-flash",
                "messages": [
                    {"role": "assistant", "content": [
                        {"type": "text", "text": "<think>step one</think>the answer"}
                    ], "tool_calls": [
                        {"id": "call_1", "type": "function", "function": {"name": "read", "arguments": "{}"}}
                    ]}
                ]
            }
            """.trimIndent(),
        )
        val normalized = ChatReasoningContentNormalizer.normalize(input, "deepseek-flash", "https://api.deepseek.com")
        val message = normalized["messages"]!!.jsonArray[0].jsonObject

        assertEquals("step one", message["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals(
            "the answer",
            message["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `an unparsable base url still matches on the model name`() {
        val normalized = ChatReasoningContentNormalizer.normalize(
            toolTurnBody(), "deepseek-flash", "not a url",
        )

        assertEquals(
            " ",
            normalized["messages"]!!.jsonArray[1].jsonObject["reasoning_content"]!!.jsonPrimitive.content,
        )
    }
}
