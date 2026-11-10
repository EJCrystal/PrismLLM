package dev.soven.prismllm.proxy

import dev.soven.prismllm.admin.domain.IqQuestion
import dev.soven.prismllm.admin.domain.normalizeIqAnswer
import dev.soven.prismllm.shared.domain.GatewayProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IqQuestionTestTest {
    private fun question(id: Long, enabled: Boolean = true) = IqQuestion(id, "题$id", "请给出结果", "text", "SECRET_ANSWER", enabled, 0)

    @Test
    fun random_selection_only_includes_enabled_questions_and_handles_zero_or_one() {
        assertNull(selectIqQuestion(emptyList(), Random(1)))
        assertNull(selectIqQuestion(listOf(question(1, false)), Random(1)))
        assertEquals(2L, selectIqQuestion(listOf(question(1, false), question(2)), Random(1))?.id)
        val random = Random(42)
        val selected = (1..100).map { selectIqQuestion(listOf(question(1), question(2), question(3, false)), random)?.id }.toSet()
        assertEquals(setOf(1L, 2L), selected)
    }

    @Test
    fun prompt_contains_neither_expected_answer_nor_question_title() {
        val prompt = iqQuestionPrompt(question(1))
        assertFalse(prompt.contains("SECRET_ANSWER"))
        assertFalse(prompt.contains("题1"))
        assertTrue(prompt.contains("不得联网搜索"))
    }

    @Test
    fun numeric_comparison_supports_decimals_and_numeric_strings_without_rounding() {
        for (answer in listOf("21", "21.0", "\"21\"", "2.1e1")) {
            assertEquals(IqVerdict.PASSED, evaluateIqQuestion("""{"answer":$answer}""", "number", "21").verdict)
        }
        assertEquals(IqVerdict.INCORRECT, evaluateIqQuestion("""{"answer":0.10000000000000001}""", "number", "0.1").verdict)
        assertEquals(IqVerdict.PASSED, evaluateIqQuestion("""{"answer":0.25}""", "number", "0.250").verdict)
        assertNull(normalizeIqAnswer("number", "1e9999999"))
        assertNull(normalizeIqAnswer("number", "NaN"))
    }

    @Test
    fun accepted_numbers_remain_valid_after_normalization_at_scale_and_length_limits() {
        assertNull(normalizeIqAnswer("number", "10e10000"))
        assertNull(normalizeIqAnswer("number", "-100e10000"))
        assertNull(normalizeIqAnswer("number", "9".repeat(1994) + "e9999"))
        for (answer in listOf("10e9999", "1e10000", "1e-10000", "0e10000", "21.000", "9".repeat(2000), "9".repeat(1991) + "e9999")) {
            val normalized = requireNotNull(normalizeIqAnswer("number", answer)) { answer }
            assertEquals(normalized, normalizeIqAnswer("number", normalized), answer)
            assertEquals(IqVerdict.PASSED, evaluateIqQuestion("""{"answer":$answer}""", "number", normalized).verdict)
        }
    }

    @Test
    fun text_comparison_trims_only_outer_whitespace_and_respects_case() {
        assertEquals(IqVerdict.PASSED, evaluateIqQuestion("""{"answer":"  Cat  "}""", "text", "Cat").verdict)
        assertEquals(IqVerdict.INCORRECT, evaluateIqQuestion("""{"answer":"cat"}""", "text", "Cat").verdict)
        assertEquals(IqVerdict.FAILED, evaluateIqQuestion("""{"answer":123}""", "text", "123").verdict)
        assertEquals(IqVerdict.PASSED, evaluateIqQuestion("""{"answer":"true"}""", "text", "true").verdict)
    }

    @Test
    fun invalid_json_missing_wrong_or_empty_answer_fails() {
        for (text in listOf("bad", "{}", """{"answer":null}""", """{"answer":true}""", """{"answer":[]}""", """{"answer":" "}""")) {
            for (type in listOf("text", "number")) assertEquals(IqVerdict.FAILED, evaluateIqQuestion(text, type, "1").verdict, text)
        }
    }

    @Test
    fun all_protocols_exclude_injected_search_tools_history_and_answers() {
        val injection = Json.parseToJsonElement("""{"tools":[{"type":"web_search"}],"tool_choice":"required","enable_search":true,"web_search_options":{},"input":"SECRET_ANSWER","instructions":"SECRET_ANSWER","messages":[{"role":"assistant","content":"SECRET_ANSWER"}],"contents":[],"previous_response_id":"old","conversation":"old","system":"SECRET_ANSWER","generationConfig":{"temperature":0.2,"tools":[{}]},"temperature":0.5}""").jsonObject
        for (protocol in GatewayProtocol.entries) {
            val original = Json.parseToJsonElement(buildModelTestBody(protocol, "model", "question only", 128)).jsonObject
            val isolated = isolateIqRequestBody(original, injection, protocol)
            assertFalse(isolated.toString().contains("SECRET_ANSWER"), protocol.value)
            assertFalse(isolated.toString().contains("tools"), protocol.value)
            assertFalse(isolated.toString().contains("search"), protocol.value)
            assertTrue(isolated.toString().contains("question only"), protocol.value)
        }
    }
}
