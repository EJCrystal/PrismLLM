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
}
