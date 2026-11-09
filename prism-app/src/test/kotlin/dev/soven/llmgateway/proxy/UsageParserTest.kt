package dev.soven.llmgateway.proxy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 验证用量解析只接受明确返回的字段，不把缺失值猜成零。
 *
 * @author 文杰
 * @since 2026-10-21
 */
class UsageParserTest {
    @Test
    fun `parses usage from final sse chunk`() {
        val line = """data: {"usage":{"prompt_tokens":10,"completion_tokens":3,"total_tokens":13}}"""

        val usage = UsageParser.fromSseLine(line)

        assertEquals(10, usage?.input)
        assertEquals(3, usage?.output)
        assertEquals(13, usage?.total)
        assertTrue(usage?.known == true)
    }

    @Test
    fun `parses cached tokens from prompt details`() {
        val line = """data: {"usage":{"prompt_tokens":100,"completion_tokens":5,"total_tokens":105,"prompt_tokens_details":{"cached_tokens":80}}}"""

        val usage = UsageParser.fromSseLine(line)

        assertEquals(80, usage?.cached)
        assertEquals(105, usage?.total)
    }

    @Test
    fun `parses reasoning tokens from completion details`() {
        val line = """data: {"usage":{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150,"completion_tokens_details":{"reasoning_tokens":32}}}"""

        val usage = UsageParser.fromSseLine(line)

        assertEquals(32, usage?.reasoning)
        assertEquals(50, usage?.output)
        assertNull(usage?.cached)
    }

    @Test
    fun `parses deepseek cache hit tokens`() {
        val line = """data: {"usage":{"prompt_tokens":100,"completion_tokens":5,"total_tokens":105,"prompt_cache_hit_tokens":80,"prompt_cache_miss_tokens":20}}"""

        val usage = UsageParser.fromSseLine(line)

        assertEquals(80, usage?.cached)
        assertEquals(100, usage?.input)
    }

    @Test
    fun `ignores done malformed and non data lines`() {
        assertNull(UsageParser.fromSseLine("data: [DONE]"))
        assertNull(UsageParser.fromSseLine("data: {not-json}"))
        assertNull(UsageParser.fromSseLine("event: message"))
        assertNull(UsageParser.fromSseLine(""))
    }

    @Test
    fun `returns null when usage object is empty`() {
        assertNull(UsageParser.fromSseLine("""data: {"usage":{}}"""))
    }

    @Test
    fun `parses responses usage from completed event`() {
        val json = Json.parseToJsonElement(
            """{"type":"response.completed","response":{"usage":{"input_tokens":10,"input_tokens_details":{"cached_tokens":2},"output_tokens":3,"total_tokens":13}}}""",
        ).jsonObject

        val usage = UsageParser.fromResponsesJson(json)

        assertEquals(10, usage?.input)
        assertEquals(3, usage?.output)
        assertEquals(2, usage?.cached)
        assertEquals(13, usage?.total)
        assertNull(usage?.reasoning)
    }

    @Test
    fun `parses responses usage from non streaming response`() {
        val json = Json.parseToJsonElement(
            """{"object":"response","usage":{"input_tokens":20,"output_tokens":4,"total_tokens":24}}""",
        ).jsonObject

        val usage = UsageParser.fromResponsesJson(json)

        assertEquals(20, usage?.input)
        assertEquals(4, usage?.output)
        assertEquals(24, usage?.total)
        assertNull(usage?.cached)
    }

    @Test
    fun `does not invent zero for missing fields`() {
        val line = """data: {"usage":{"total_tokens":13}}"""

        val usage = UsageParser.fromSseLine(line)

        assertEquals(13, usage?.total)
        assertNull(usage?.input)
        assertNull(usage?.output)
        assertFalse(usage?.known == false)
    }

    @Test
    fun `parses anthropic non streaming usage and cache reads`() {
        val json = Json.parseToJsonElement(
            """{"type":"message","usage":{"input_tokens":10,"cache_creation_input_tokens":2,"cache_read_input_tokens":3,"output_tokens":4}}""",
        ).jsonObject

        val usage = UsageParser.fromAnthropicJson(json)

        assertEquals(15, usage?.input)
        assertEquals(4, usage?.output)
        assertEquals(3, usage?.cached)
        assertEquals(2, usage?.cacheWrite)
        assertEquals(19, usage?.total)
        assertNull(usage?.reasoning)
    }

    @Test
    fun `parses anthropic reasoning and thinking tokens from usage details`() {
        val jsonWithDetails = Json.parseToJsonElement(
            """{"type":"message","usage":{"input_tokens":10,"output_tokens":40,"output_tokens_details":{"reasoning_tokens":25}}}""",
        ).jsonObject
        val usageWithDetails = UsageParser.fromAnthropicJson(jsonWithDetails)
        assertEquals(25, usageWithDetails?.reasoning)
        assertEquals(40, usageWithDetails?.output)

        val jsonWithThinking = Json.parseToJsonElement(
            """{"type":"message","usage":{"input_tokens":10,"output_tokens":40,"thinking_tokens":30}}""",
        ).jsonObject
        val usageWithThinking = UsageParser.fromAnthropicJson(jsonWithThinking)
        assertEquals(30, usageWithThinking?.reasoning)
    }

    @Test
    fun `parses cache write tokens from prompt details in chat completions`() {
        val line = """data: {"usage":{"prompt_tokens":100,"completion_tokens":5,"total_tokens":105,"prompt_tokens_details":{"cached_tokens":20,"cache_write_tokens":10}}}"""

        val usage = UsageParser.fromSseLine(line)

        assertEquals(20, usage?.cached)
        assertEquals(10, usage?.cacheWrite)
        assertEquals(100, usage?.input)
    }

    @Test
    fun `parses cache write tokens from responses api`() {
        val json = Json.parseToJsonElement(
            """{"type":"response.completed","response":{"usage":{"input_tokens":50,"input_tokens_details":{"cached_tokens":15,"cache_creation_tokens":25},"output_tokens":5,"total_tokens":55}}}""",
        ).jsonObject

        val usage = UsageParser.fromResponsesJson(json)

        assertEquals(50, usage?.input)
        assertEquals(15, usage?.cached)
        assertEquals(25, usage?.cacheWrite)
    }

    @Test
    fun `parses anthropic streaming usage from message start and delta`() {
        val start = Json.parseToJsonElement(
            """{"type":"message_start","message":{"usage":{"input_tokens":10,"cache_read_input_tokens":3,"output_tokens":1}}}""",
        ).jsonObject
        val delta = Json.parseToJsonElement(
            """{"type":"message_delta","usage":{"output_tokens":4}}""",
        ).jsonObject

        val startUsage = UsageParser.fromAnthropicJson(start)
        val deltaUsage = UsageParser.fromAnthropicJson(delta)

        assertEquals(13, startUsage?.input)
        assertEquals(3, startUsage?.cached)
        assertEquals(1, startUsage?.output)
        assertNull(startUsage?.total)
        assertEquals(4, deltaUsage?.output)
        assertNull(deltaUsage?.input)
        assertNull(UsageParser.fromAnthropicJson(Json.parseToJsonElement("""{"type":"content_block_delta"}""").jsonObject))
    }
}
