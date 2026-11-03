package dev.soven.llmgateway.proxy.protocol

import kotlinx.serialization.json.JsonObject
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
