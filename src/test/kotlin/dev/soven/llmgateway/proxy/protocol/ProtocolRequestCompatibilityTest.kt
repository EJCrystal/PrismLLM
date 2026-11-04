package dev.soven.llmgateway.proxy.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtocolRequestCompatibilityTest {
    private val budgets = mapOf("minimal" to 1024L, "low" to 1024L, "medium" to 4096L, "high" to 8192L, "xhigh" to 16384L, "max" to 16384L)

    @Test
    fun defaultTokenLimitsAccommodateThinkingBudgets() {
        budgets.forEach { (effort, budget) ->
            requests(effort, null).forEach { (converter, request, _) ->
                val converted = converter.convertRequest(request, "upstream")
                assertEquals(maxOf(4096L, budget + 1), converted.long("max_tokens"))
                assertEquals(budget, converted["thinking"]!!.jsonObject.long("budget_tokens"))
            }
        }
    }

    @Test
    fun explicitTokenLimitsArePreservedWhileThinkingBudgetsAreClamped() {
        budgets.forEach { (effort, budget) ->
            listOf(1025L, 2048L, 4096L, 8192L, 16384L, 16385L, 32768L, Long.MAX_VALUE).forEach { limit ->
                requests(effort, limit).forEach { (converter, request, _) ->
                    val converted = converter.convertRequest(request, "upstream")
                    val actualBudget = converted["thinking"]!!.jsonObject.long("budget_tokens")!!
                    assertEquals(limit, converted.long("max_tokens"))
                    assertEquals(minOf(budget, limit - 1), actualBudget)
                    assertTrue(actualBudget >= 1024 && actualBudget < limit)
                }
            }
        }
    }

    @Test
    fun impossibleExplicitLimitsAreRejectedAtTheirSourceField() {
        budgets.keys.forEach { effort ->
            listOf(Long.MIN_VALUE, -1L, 0L, 1L, 1023L, 1024L).forEach { limit ->
                requests(effort, limit).forEach { (converter, request, field) ->
                    val error = assertFailsWith<ProtocolConversionException> {
                        converter.convertRequest(request, "upstream")
                    }
                    assertEquals(field, error.field)
                }
            }
        }
    }

    @Test
    fun absentOrDisabledThinkingDoesNotChangeTokenLimits() {
        listOf(null, "none").forEach { effort ->
            listOf(null, 64L, 4096L, 32768L).forEach { limit ->
                requests(effort, limit).forEach { (converter, request, _) ->
                    val converted = converter.convertRequest(request, "upstream")
                    assertEquals(limit ?: 4096L, converted.long("max_tokens"))
                    if (effort == null) {
                        assertFalse(converted.containsKey("thinking"))
                    } else {
                        val thinking = converted["thinking"]!!.jsonObject
                        assertEquals("disabled", thinking.string("type"))
                        assertFalse(thinking.containsKey("budget_tokens"))
                    }
                }
            }
        }
    }

    @Test
    fun chatMaxTokensRetainsPrecedenceOverMaxCompletionTokens() {
        val request = buildJsonObject {
            put("messages", buildJsonArray { })
            put("reasoning_effort", "high")
            put("max_tokens", 2048)
            put("max_completion_tokens", 64)
        }
        val converted = ChatCompletionToMessages.convertRequest(request, "upstream")
        assertEquals(2048L, converted.long("max_tokens"))
        assertEquals(2047L, converted["thinking"]!!.jsonObject.long("budget_tokens"))
    }

    @Test
    fun responsesParallelToolPreferenceRoundTripsWithoutInventingDefaults() {
        listOf(null, false, true).forEach { preference ->
            val request = buildJsonObject {
                put("input", "hello")
                preference?.let { put("parallel_tool_calls", it) }
            }
            val chat = ResponsesToChatCompletion.convertRequest(request, "upstream")
            val back = ChatCompletionToResponses.convertRequest(chat, "public")
            listOf(chat, back).forEach { converted ->
                assertEquals(preference, converted.boolean("parallel_tool_calls"))
                assertEquals(preference != null, converted.containsKey("parallel_tool_calls"))
            }
        }
    }

    @Test
    fun parallelResponsesCallsShareOneAssistantMessageAndPreserveText() {
        val request = Json.parseToJsonElement("""{"input":[
            {"role":"assistant","content":[{"type":"output_text","text":"checking"}]},
            {"type":"function_call","call_id":"a","id":"internal-a","name":"first","arguments":"{\"city\":\"SZ\"}"},
            {"type":"function_call","id":"b","name":"second","arguments":""},
            {"type":"function_call_output","call_id":"a","output":"sunny"},
            {"type":"function_call_output","call_id":"b","output":"warm"}
        ]}""").jsonObject
        listOf(true, false).forEach { withText ->
            val input = if (withText) request else JsonObject(request + ("input" to JsonArray(request.array("input").drop(1))))
            val converted = ResponsesToChatCompletion.convertRequest(input, "upstream")
            val messages = converted.array("messages").map { it.jsonObject }
            assertEquals(listOf("assistant", "tool", "tool"), messages.map { it.string("role") })
            assertEquals(if (withText) "checking" else null, messages[0].string("content"))
            val calls = messages[0].array("tool_calls").map { it.jsonObject }
            assertEquals(listOf("a", "b"), calls.map { it.string("id") })
            assertEquals(listOf("first", "second"), calls.map { it["function"]!!.jsonObject.string("name") })
            assertEquals(listOf("{\"city\":\"SZ\"}", "{}"), calls.map { it["function"]!!.jsonObject.string("arguments") })
            assertEquals(listOf("a", "b"), messages.drop(1).map { it.string("tool_call_id") })
            val back = ChatCompletionToResponses.convertRequest(converted, "public")
            assertEquals(input.array("input").map { it.jsonObject.string("type") ?: "message" }, back.array("input").map { it.jsonObject.string("type") })
        }
    }

    @Test
    fun toolOutputsAndOtherRolesStartNewAssistantToolGroups() {
        val request = Json.parseToJsonElement("""{"input":[
            {"role":"user","content":"start"},
            {"type":"function_call","call_id":"a","name":"first"},
            {"type":"function_call_output","call_id":"a","output":"A"},
            {"type":"function_call","call_id":"b","name":"second"},
            {"type":"function_call_output","call_id":"b","output":"B"},
            {"role":"user","content":"next"},
            {"type":"function_call","call_id":"c","name":"third"},
            {"type":"function_call_output","call_id":"c","output":"C"}
        ]}""").jsonObject
        val messages = ResponsesToChatCompletion.convertRequest(request, "upstream").array("messages").map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "tool", "assistant", "tool", "user", "assistant", "tool"), messages.map { it.string("role") })
        assertEquals(listOf("a", "b", "c"), messages.filter { it.string("role") == "assistant" }.map { it.array("tool_calls").single().jsonObject.string("id") })
    }

    @Test
    fun messagesBuiltinAndInvalidToolTypesAreRejectedInBothDirections() {
        val invalidTypes = listOf("web_search_20250305", "computer_20250124", "code_execution_20250522").map { JsonPrimitive(it) } +
            listOf(JsonNull, buildJsonObject { }, JsonPrimitive(42))
        listOf(MessagesToChatCompletion, MessagesToResponses).forEach { converter ->
            invalidTypes.forEach { type ->
                val request = buildJsonObject {
                    put("messages", buildJsonArray { })
                    put("tools", buildJsonArray {
                        add(buildJsonObject { put("name", "custom") })
                        add(buildJsonObject { put("type", type); put("name", "builtin") })
                    })
                }
                val error = assertFailsWith<ProtocolConversionException> { converter.convertRequest(request, "upstream") }
                assertEquals("tools[1].type", error.field)
            }
            listOf(null, "custom").forEach { type ->
                val request = buildJsonObject {
                    put("messages", buildJsonArray { })
                    put("tools", buildJsonArray {
                        add(buildJsonObject {
                            type?.let { put("type", it) }
                            put("name", "lookup")
                            put("description", "weather")
                            put("input_schema", buildJsonObject { put("type", "object") })
                        })
                    })
                }
                val tool = converter.convertRequest(request, "upstream").array("tools").single().jsonObject
                val function = (tool["function"] as? JsonObject) ?: tool
                assertEquals("lookup", function.string("name"))
                assertEquals("weather", function.string("description"))
                assertEquals("object", function["parameters"]!!.jsonObject.string("type"))
            }
        }
    }

    private fun requests(effort: String?, limit: Long?): List<Triple<ProtocolConverter, JsonObject, String>> = listOf(
        ChatCompletionToMessages to "max_tokens",
        ChatCompletionToMessages to "max_completion_tokens",
        ResponsesToMessages to "max_output_tokens",
    ).map { (converter, field) ->
        val request = buildJsonObject {
            if (converter == ResponsesToMessages) {
                put("input", "hello")
                effort?.let { putJsonObject("reasoning") { put("effort", it) } }
            } else {
                put("messages", buildJsonArray { })
                effort?.let { put("reasoning_effort", it) }
            }
            limit?.let { put(field, it) }
        }
        Triple(converter, request, field)
    }
}
