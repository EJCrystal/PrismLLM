package dev.soven.prismllm.protocol

import dev.soven.prismllm.shared.domain.GatewayProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Codex Responses Lite 的 additional_tools 抬升回归。
 *
 * @author 文杰
 * @since 2026-11-10
 */
class AdditionalToolsTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `request without additional_tools is returned unchanged`() {
        val request = json(
            """{"model":"gpt","input":[{"type":"message","role":"user","content":"hi"}],
                "tools":[{"type":"function","name":"lookup","parameters":{"type":"object"}}]}""",
        )
        assertSame(request, liftAdditionalTools(request))
    }

    @Test
    fun `additional_tools lift into request level tools`() {
        val request = json(
            """{
              "model":"gpt-6",
              "input":[
                {"type":"additional_tools","role":"developer","tools":[
                  {"type":"namespace","name":"functions","tools":[
                    {"type":"function","name":"shell","description":"run a command","parameters":{"type":"object"}},
                    {"type":"function","name":"lookup","parameters":{"type":"object"}}
                  ]}
                ]},
                {"type":"message","role":"developer","content":[{"type":"input_text","text":"be brief"}]},
                {"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]}
              ]
            }""",
        )
        val lifted = liftAdditionalTools(request)
        // 输入项里不再有 additional_tools；developer prompt 与用户消息保留原顺序。
        val input = lifted["input"]!!.jsonArray
        assertEquals(2, input.size)
        assertEquals("message", input[0].jsonObject.string("type"))
        assertEquals("developer", input[0].jsonObject.string("role"))
        assertEquals("user", input[1].jsonObject.string("role"))
        // namespace 拆开平铺为请求级 function 工具。
        val tools = lifted["tools"]!!.jsonArray
        assertEquals(2, tools.size)
        assertEquals("function", tools[0].jsonObject.string("type"))
        assertEquals("shell", tools[0].jsonObject.string("name"))
        assertEquals("lookup", tools[1].jsonObject.string("name"))
        // 不认识的字段不新增，model 原样保留。
        assertEquals("gpt-6", lifted["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `lifted tools merge with existing tools without duplicates`() {
        val request = json(
            """{
              "model":"gpt",
              "input":[
                {"type":"additional_tools","tools":[
                  {"type":"function","name":"shell","parameters":{"type":"object"}},
                  {"type":"function","name":"extra","parameters":{"type":"object"}}
                ]}
              ],
              "tools":[{"type":"function","name":"shell","description":"already offered","parameters":{"type":"object"}}]
            }""",
        )
        val lifted = liftAdditionalTools(request)
        val tools = lifted["tools"]!!.jsonArray
        assertEquals(2, tools.size)
        // 已有工具保持原定义（描述不丢失）。
        assertEquals("shell", tools[0].jsonObject.string("name"))
        assertEquals("already offered", tools[0].jsonObject.string("description"))
        assertEquals("extra", tools[1].jsonObject.string("name"))
    }

    @Test
    fun `function call namespace marker is stripped`() {
        val request = json(
            """{
              "model":"gpt",
              "input":[
                {"type":"additional_tools","tools":[
                  {"type":"namespace","name":"functions","tools":[
                    {"type":"function","name":"shell","parameters":{"type":"object"}}
                  ]}
                ]},
                {"type":"function_call","call_id":"call_1","name":"shell","arguments":"{}","namespace":"functions"},
                {"type":"function_call_output","call_id":"call_1","output":"done"}
              ]
            }""",
        )
        val lifted = liftAdditionalTools(request)
        val input = lifted["input"]!!.jsonArray
        assertEquals(2, input.size)
        assertEquals("function_call", input[0].jsonObject.string("type"))
        assertEquals(null, input[0].jsonObject.string("namespace"))
        assertEquals("function_call_output", input[1].jsonObject.string("type"))
    }

    @Test
    fun `repeated namespace merges only new tools`() {
        val request = json(
            """{
              "model":"gpt",
              "input":[
                {"type":"additional_tools","tools":[
                  {"type":"namespace","name":"functions","tools":[
                    {"type":"function","name":"shell","parameters":{"type":"object"}}
                  ]},
                  {"type":"function","name":"freeform","parameters":{"type":"object"}}
                ]},
                {"type":"additional_tools","tools":[
                  {"type":"namespace","name":"functions","tools":[
                    {"type":"function","name":"shell","parameters":{"type":"object"}},
                    {"type":"function","name":"update_plan","parameters":{"type":"object"}}
                  ]}
                ]}
              ]
            }""",
        )
        val lifted = liftAdditionalTools(request)
        val tools = lifted["tools"]!!.jsonArray
        assertEquals(3, tools.size)
        assertEquals(
            listOf("shell", "freeform", "update_plan"),
            tools.map { it.jsonObject.string("name") },
        )
    }

    @Test
    fun `responses to chat conversion accepts codex lite shape`() {
        val request = json(
            """{
              "model":"gpt-6",
              "store":false,
              "input":[
                {"type":"additional_tools","role":"developer","tools":[
                  {"type":"namespace","name":"functions","tools":[
                    {"type":"function","name":"shell","description":"run","parameters":{"type":"object"}}
                  ]}
                ]},
                {"type":"message","role":"developer","content":[{"type":"input_text","text":"plan first"}]},
                {"type":"message","role":"user","content":[{"type":"input_text","text":"list files"}]}
              ],
              "tool_choice":{"type":"function","name":"shell"}
            }""",
        )
        val chat = ProtocolConversion.convertRequest(GatewayProtocol.RESPONSES, GatewayProtocol.CHAT_COMPLETIONS, request, "gpt-upstream")
        // 工具以普通 function 工具到达 Chat 上游。
        val tools = chat["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        assertEquals("shell", tools[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        // developer prompt 归一化为 system 由 DeveloperRoleNormalizer 在出站策略完成；这里保留 developer 角色。
        val messages = chat["messages"]!!.jsonArray
        assertEquals("developer", messages[0].jsonObject.string("role"))
        assertEquals("plan first", messages[0].jsonObject.string("content"))
        assertEquals("user", messages[1].jsonObject.string("role"))
    }

    @Test
    fun `responses to messages conversion accepts codex lite shape`() {
        val request = json(
            """{
              "model":"gpt-6",
              "max_output_tokens":1024,
              "input":[
                {"type":"additional_tools","tools":[
                  {"type":"function","name":"shell","description":"run","parameters":{"type":"object"}}
                ]},
                {"type":"message","role":"user","content":[{"type":"input_text","text":"list files"}]}
              ]
            }""",
        )
        val messages = ProtocolConversion.convertRequest(GatewayProtocol.RESPONSES, GatewayProtocol.MESSAGES, request, "claude-upstream")
        val tools = messages["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        assertEquals("shell", tools[0].jsonObject.string("name"))
        assertEquals("list files", messages["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `responses to gemini conversion accepts codex lite shape`() {
        val request = json(
            """{
              "model":"gpt-6",
              "input":[
                {"type":"additional_tools","tools":[
                  {"type":"function","name":"shell","description":"run","parameters":{"type":"object"}}
                ]},
                {"type":"message","role":"user","content":[{"type":"input_text","text":"list files"}]}
              ]
            }""",
        )
        val gemini = ProtocolConversion.convertRequest(GatewayProtocol.RESPONSES, GatewayProtocol.GEMINI, request, "gemini-upstream")
        val declarations = gemini["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray
        assertEquals(1, declarations.size)
        assertEquals("shell", declarations[0].jsonObject.string("name"))
    }

    @Test
    fun `builtin tools inside additional_tools still refuse with a clear field`() {
        // 抬升解决的是形状问题；内置工具仍按 ADR 0013 显式拒绝（web_search 由搜索代理负责）。
        val request = json(
            """{
              "model":"gpt",
              "input":[
                {"type":"additional_tools","tools":[
                  {"type":"web_search"}
                ]},
                {"type":"message","role":"user","content":"hi"}
              ]
            }""",
        )
        val error = assertFailsWith<ProtocolConversionException> {
            ProtocolConversion.convertRequest(GatewayProtocol.RESPONSES, GatewayProtocol.CHAT_COMPLETIONS, request, "gpt")
        }
        assertEquals("tools[0].type", error.field)
    }

    @Test
    fun `hosted tool without name keeps its type key`() {
        val request = json(
            """{
              "model":"gpt",
              "input":[
                {"type":"additional_tools","tools":[
                  {"type":"web_search"},
                  {"type":"function","name":"shell","parameters":{"type":"object"}}
                ]}
              ],
              "tools":[{"type":"web_search"}]
            }""",
        )
        val lifted = liftAdditionalTools(request)
        val tools = lifted["tools"]!!.jsonArray
        assertEquals(2, tools.size)
        assertEquals("web_search", tools[0].jsonObject.string("type"))
        assertEquals("shell", tools[1].jsonObject.string("name"))
    }
}
