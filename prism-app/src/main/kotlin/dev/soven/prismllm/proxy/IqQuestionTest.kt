package dev.soven.prismllm.proxy

import dev.soven.prismllm.admin.domain.IqQuestion
import dev.soven.prismllm.admin.domain.normalizeIqAnswer
import dev.soven.prismllm.spi.iq.IqStructureCheck
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

internal fun selectIqQuestion(questions: List<IqQuestion>, random: Random = Random.Default): IqQuestion? =
    questions.filter { it.enabled }.randomOrNull(random)

/** Only the question and generic output requirements are included, never the expected answer. */
internal fun iqQuestionPrompt(question: IqQuestion): String = buildString {
    append(question.prompt)
    append("\n\n请独立推理，不得联网搜索，不得调用任何工具。只返回一个 JSON 对象，不要输出 HTML 或额外文字。")
    append(if (question.answerType == "number") {
        "格式：{\"answer\": <数字>, \"reason\": \"简要推导过程\"}。answer 必须是数字。"
    } else {
        "格式：{\"answer\": \"最终答案\", \"reason\": \"简要推导过程\"}。answer 必须是字符串。"
    })
}

internal fun evaluateIqQuestion(text: String, answerType: String, expectedAnswer: String): IqTestEvaluation {
    val payload = extractJsonObject(text)
    val primitive = payload?.get("answer") as? JsonPrimitive
    val raw = primitive?.takeUnless { it == JsonNull || (!it.isString && answerType == "text") }
        ?.content
    // Booleans and null must never be accepted as text or numbers.
    val actual = raw?.let { normalizeIqAnswer(answerType, it) }
    val matched = actual != null && actual == normalizeIqAnswer(answerType, expectedAnswer)
    val verdict = when {
        actual == null -> IqVerdict.FAILED
        matched -> IqVerdict.PASSED
        else -> IqVerdict.INCORRECT
    }
    val legacyExpected = if (answerType == "number") expectedAnswer.toBigDecimalOrNull()?.let {
        runCatching { it.intValueExact() }.getOrNull()
    } else null
    return IqTestEvaluation(
        verdict = verdict,
        expectedAnswer = legacyExpected,
        matchedAnswer = legacyExpected.takeIf { matched },
        actualAnswer = actual,
        checks = listOf(
            IqStructureCheck("json", "返回可解析的 JSON 对象", payload != null),
            IqStructureCheck("answer-type", if (answerType == "number") "answer 是有效数字" else "answer 是非空字符串", actual != null),
            IqStructureCheck("answer", "answer 与标准答案一致", matched,
                if (actual == null) "answer 缺失、为空或类型不符" else "实际答案：$actual；期望答案：$expectedAnswer"),
        ),
    )
}
