package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.spi.iq.IqJudgeResult
import dev.soven.llmgateway.spi.iq.IqJudgeVerdict
import dev.soven.llmgateway.spi.iq.IqStructureCheck
import dev.soven.llmgateway.spi.iq.IqStructureSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 智商检测的内置题目。
 *
 * 两个题目对应当前社区常用的“鹈鹕骑行”和“糖果测试”。每道题都带一份内置提示词，
 * 管理员可以在运行时替换提示词，便于调整题目细节；历史记录会保留当次实际使用的提示词。
 *
 * 判分器可插拔：鹈鹕由运行时选中的 IqJudge 判定；
 * 糖果测试只要求模型返回 JSON，answer 等于官方答案 21 即通过。
 */
enum class IqBenchmark(
    val value: String,
    val label: String,
    val summary: String,
    val grading: String,
    val maxOutputTokens: Int,
    val timeoutMs: Long,
) {
    PELICAN(
        value = "pelican",
        label = "鹈鹕骑行",
        summary = "生成可独立运行的 SVG 动画：一只骑自行车的鹈鹕，带连续循环动画。",
        grading = "本地生成 SVG/HTML 后由选中的判分器判定；选择满血AI 时 normal 通过，degraded 未通过，unknown 无法判定。",
        maxOutputTokens = 12_000,
        timeoutMs = 180_000,
    ),
    CANDY(
        value = "candy",
        label = "糖果测试",
        summary = "返回 JSON，给出糖果组合推理题的最终答案与推导过程。",
        grading = "JSON 中的 answer 等于 21 即通过；不使用 HTML 判分器。",
        maxOutputTokens = 8_192,
        timeoutMs = 120_000,
    ),
    ;

    val prompt: String
        get() = when (this) {
            PELICAN -> PELICAN_PROMPT
            CANDY -> CANDY_PROMPT
        }

    companion object {
        fun fromValue(value: String?): IqBenchmark? =
            entries.firstOrNull { it.value == value?.trim()?.lowercase() }
    }
}

enum class IqVerdict(val value: String) {
    PASSED("passed"),
    INCORRECT("incorrect"),
    REVIEW("review"),
    FAILED("failed"),
}

data class IqTestEvaluation(
    val verdict: IqVerdict,
    val checks: List<IqStructureCheck>,
    val html: String? = null,
    val expectedAnswer: Int? = null,
    val matchedAnswer: Int? = null,
)

fun evaluateIqTest(
    benchmark: IqBenchmark,
    success: Boolean,
    responseText: String?,
    errorMessage: String? = null,
    structure: IqStructureSummary? = null,
    judgeResult: IqJudgeResult? = null,
    manualVerdict: String? = null,
): IqTestEvaluation {
    if (!success) {
        return IqTestEvaluation(
            verdict = IqVerdict.FAILED,
            checks = listOf(IqStructureCheck("request", "上游请求成功", false, errorMessage?.take(400))),
        )
    }
    val text = responseText?.trim().orEmpty()
    if (text.isEmpty()) {
        return IqTestEvaluation(
            verdict = IqVerdict.FAILED,
            checks = listOf(IqStructureCheck("response", "上游返回文本", false, "上游响应为空")),
        )
    }
    return when (benchmark) {
        IqBenchmark.PELICAN -> {
            val summary = structure ?: analyzePelicanStructure(text)
            evaluatePelican(text, summary, judgeResult, manualVerdict)
        }
        IqBenchmark.CANDY -> evaluateCandy(text)
    }
}

/** 鹈鹕作品的本地结构分析；判分插件与页面展示共用同一份结果。 */
internal fun analyzePelicanStructure(raw: String?): IqStructureSummary {
    val html = extractHtmlDocument(raw)
    val hasDocument = html != null
    val hasSvg = html?.contains("<svg", ignoreCase = true) == true
    val hasAnimation = html?.let { ANIMATION_PATTERN.containsMatchIn(it) } == true
    val selfContained = html?.let { !EXTERNAL_RESOURCE_PATTERN.containsMatchIn(it) } == true
    val checks = listOf(
        IqStructureCheck(
            id = "html",
            label = "可解析为 HTML/SVG",
            passed = hasDocument,
            detail = if (hasDocument) null else "未找到 HTML 文档或 SVG 片段",
        ),
        IqStructureCheck(
            id = "svg",
            label = "包含内联 SVG",
            passed = hasSvg,
            detail = if (hasSvg) null else "未找到 <svg> 元素",
        ),
        IqStructureCheck(
            id = "animation",
            label = "包含循环动画",
            passed = hasAnimation,
            detail = if (hasAnimation) null else "未检测到 CSS/SMIL 动画",
        ),
        IqStructureCheck(
            id = "self-contained",
            label = "不依赖外部资源",
            passed = selfContained,
            detail = if (selfContained) null else "检测到 http(s)、协议相对地址或 @import",
        ),
    )
    return IqStructureSummary(
        checks = checks,
        htmlPresent = hasDocument,
        svgPresent = hasSvg,
        animationPresent = hasAnimation,
        selfContained = selfContained,
    )
}

private fun evaluatePelican(
    text: String,
    structure: IqStructureSummary,
    judgeResult: IqJudgeResult?,
    manualVerdict: String?,
): IqTestEvaluation {
    val html = extractHtmlDocument(text)
    val verdict = when {
        // 人工判定优先级最高，允许管理员推翻自动判分。
        manualVerdict == "passed" -> IqVerdict.PASSED
        manualVerdict == "incorrect" -> IqVerdict.INCORRECT
        !structure.htmlPresent || !structure.svgPresent -> IqVerdict.FAILED
        judgeResult != null -> judgeResult.verdict.toIqVerdict()
        structure.allPassed -> IqVerdict.PASSED
        else -> IqVerdict.REVIEW
    }
    return IqTestEvaluation(verdict = verdict, checks = structure.checks, html = html)
}

private fun evaluateCandy(text: String): IqTestEvaluation {
    val payload = parseCandyPayload(text)
    if (payload == null) {
        return IqTestEvaluation(
            verdict = IqVerdict.FAILED,
            checks = listOf(
                IqStructureCheck(
                    id = "json",
                    label = "返回可解析的 JSON 对象",
                    passed = false,
                    detail = "未找到包含 answer 和 reason 的 JSON 对象",
                ),
            ),
            expectedAnswer = CANDY_EXPECTED_ANSWER,
        )
    }
    val answer = payload.answer
    if (answer == null) {
        return IqTestEvaluation(
            verdict = IqVerdict.FAILED,
            checks = listOf(
                IqStructureCheck(id = "json", label = "返回可解析的 JSON 对象", passed = true),
                IqStructureCheck(
                    id = "answer",
                    label = "answer 是整数",
                    passed = false,
                    detail = "answer 缺失或不是整数",
                ),
            ),
            expectedAnswer = CANDY_EXPECTED_ANSWER,
        )
    }
    val matched = answer == CANDY_EXPECTED_ANSWER
    val detail = buildString {
        append("answer 为 $answer")
        append(if (matched) "，与期望一致" else "，期望 $CANDY_EXPECTED_ANSWER")
        payload.reason?.take(400)?.let { append("；推理：$it") }
    }
    return IqTestEvaluation(
        verdict = if (matched) IqVerdict.PASSED else IqVerdict.INCORRECT,
        checks = listOf(
            IqStructureCheck(id = "json", label = "返回可解析的 JSON 对象", passed = true),
            IqStructureCheck(id = "answer", label = "answer 等于 $CANDY_EXPECTED_ANSWER", passed = matched, detail = detail),
        ),
        expectedAnswer = CANDY_EXPECTED_ANSWER,
        matchedAnswer = if (matched) CANDY_EXPECTED_ANSWER else null,
    )
}

/** 旧版糖果记录按当时保存的 HTML 结论展示，升级后不重新判分。 */
internal fun evaluateLegacyCandy(
    responseText: String?,
    verdict: String,
    expectedAnswer: Int?,
    matchedAnswer: Int?,
): IqTestEvaluation {
    val expected = expectedAnswer ?: CANDY_EXPECTED_ANSWER
    val matched = matchedAnswer != null
    return IqTestEvaluation(
        verdict = IqVerdict.entries.firstOrNull { it.value == verdict } ?: IqVerdict.FAILED,
        checks = listOf(
            IqStructureCheck(
                id = "answer",
                label = "最终答案包含 $expected",
                passed = matched,
                detail = if (matched) {
                    "旧版规则：找到独立答案 $expected"
                } else {
                    "旧版规则：未找到独立答案 $expected"
                },
            ),
        ),
        html = extractHtmlDocument(responseText),
        expectedAnswer = expectedAnswer,
        matchedAnswer = matchedAnswer,
    )
}

private data class CandyPayload(val answer: Int?, val reason: String?)

private fun parseCandyPayload(raw: String): CandyPayload? {
    val json = extractJsonObject(raw) ?: return null
    val answer = json["answer"]?.let { element ->
        val content = (element as? JsonPrimitive)?.contentOrNull?.trim() ?: return@let null
        content.toIntOrNull() ?: content.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }
            ?.toInt()
    }
    val reason = (json["reason"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    return CandyPayload(answer, reason)
}

/**
 * 容忍代码围栏和前后说明；依次尝试每个 `{` 起点，返回第一个能解析为 JSON 对象且括号配对的片段。
 * 说明文字里出现花括号（例如“集合 {圆苹果, 星桃}”）时不会挡住后面的真正答案。
 */
private fun extractJsonObject(raw: String): JsonObject? {
    val text = raw.trim()
    val fenced = JSON_FENCE_PATTERN.find(text)?.groupValues?.get(1)?.trim()
    return listOfNotNull(fenced, text)
        .firstNotNullOfOrNull { candidate ->
            jsonObjectCandidates(candidate).firstNotNullOfOrNull { json ->
                runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
            }
        }
}

/** 最多尝试 [MAX_JSON_CANDIDATES] 个起点，避免超长响应导致重复扫描。 */
private fun jsonObjectCandidates(source: String): Sequence<String> =
    generateSequence(source.indexOf('{').takeIf { it >= 0 }) { start ->
        source.indexOf('{', start + 1).takeIf { it >= 0 }
    }
        .take(MAX_JSON_CANDIDATES)
        .mapNotNull { start -> balancedJsonObject(source, start) }

private fun balancedJsonObject(source: String, start: Int): String? {
    var depth = 0
    var inString = false
    var escaped = false
    for (index in start until source.length) {
        val char = source[index]
        if (inString) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
            continue
        }
        when (char) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return source.substring(start, index + 1)
            }
        }
    }
    return null
}

private fun IqJudgeVerdict.toIqVerdict(): IqVerdict = when (this) {
    IqJudgeVerdict.PASSED -> IqVerdict.PASSED
    IqJudgeVerdict.INCORRECT -> IqVerdict.INCORRECT
    IqJudgeVerdict.REVIEW -> IqVerdict.REVIEW
    // 判分器自身失败（超时、插件异常等）不等于模型作品失败，交给人工确认。
    IqJudgeVerdict.FAILED -> IqVerdict.REVIEW
}

/** 从模型输出中提取 HTML 文档；支持 Markdown 代码围栏和纯 SVG 片段。 */
internal fun extractHtmlDocument(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    var text = raw.trim()
    HTML_FENCE_PATTERN.findAll(text)
        .map { it.groupValues[1] }
        .firstOrNull { it.contains("<html", ignoreCase = true) || it.contains("<svg", ignoreCase = true) }
        ?.let { text = it.trim() }
    val lower = text.lowercase()
    val documentStart = listOf(lower.indexOf("<!doctype html"), lower.indexOf("<html"))
        .filter { it >= 0 }
        .minOrNull()
    if (documentStart != null) {
        val end = lower.lastIndexOf("</html>")
        return if (end > documentStart) {
            text.substring(documentStart, end + HTML_CLOSE.length)
        } else {
            text.substring(documentStart)
        }
    }

    val bodyStart = lower.indexOf("<body")
    if (bodyStart >= 0) {
        val end = lower.lastIndexOf("</body>")
        val body = if (end > bodyStart) {
            text.substring(bodyStart, end + BODY_CLOSE.length)
        } else {
            text.substring(bodyStart)
        }
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">$PREVIEW_STYLE</head>$body</html>"
    }

    val svgStart = lower.indexOf("<svg")
    if (svgStart < 0) return null
    val svgEnd = lower.lastIndexOf("</svg>")
    val svg = if (svgEnd > svgStart) {
        text.substring(svgStart, svgEnd + SVG_CLOSE.length)
    } else {
        text.substring(svgStart)
    }
    return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">$PREVIEW_STYLE</head><body>$svg</body></html>"
}

// 满血官方判分规则使用 21（见 docs/iq-test.md）；按组合推理算出的 29 不参与判分，不自行改题。
private const val CANDY_EXPECTED_ANSWER = 21

private const val HTML_CLOSE = "</html>"
private const val BODY_CLOSE = "</body>"
private const val SVG_CLOSE = "</svg>"

private const val PREVIEW_STYLE =
    "<style>" +
        "html,body{margin:0;min-height:100%;background:#fff}" +
        "body{display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:100vh}" +
        "body>svg{display:block;max-width:100%;max-height:100vh;height:auto}" +
        // 只有 viewBox 没有 width 的 SVG 在弹性/网格布局里会退化成 0 尺寸，必须给一个确定的宽度。
        "body>svg:not([width]){width:100%}" +
        "</style>"

private val HTML_FENCE_PATTERN = Regex("```(?:html)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
private val JSON_FENCE_PATTERN = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
private const val MAX_JSON_CANDIDATES = 20

private val ANIMATION_PATTERN = Regex(
    "(?:<animate(?:Transform|Motion)?\\b|@keyframes\\b|animation\\s*:)",
    RegexOption.IGNORE_CASE,
)

private val EXTERNAL_RESOURCE_PATTERN = Regex(
    "(?:src|href|xlink:href|poster)\\s*=\\s*[\\\"']\\s*(?:https?:)?//|@import\\b|url\\(\\s*[\\\"']?\\s*(?:https?:)?//",
    RegexOption.IGNORE_CASE,
)

private val PELICAN_PROMPT = """
请用 SVG 创作一段鹈鹕骑自行车的动画。必须有真实、连续、肉眼可见的动态效果，让车轮转动并表现自然的骑行动作，动画的节奏和细节由你自由设计。清楚表现鹈鹕的特征、自行车结构与骑行姿态，关注构图、比例、配色和细节，让画面美观且协调。场景、视角和艺术风格由你自由发挥。请使用 SVG 原生动画或内嵌 CSS 动画，返回完整、可独立运行、自动播放并循环的 SVG 动画代码，不依赖外部资源。所有可见图形必须直接写在 SVG 标签内，不使用 JavaScript 创建图形，不返回等待脚本填充的空 SVG 容器；动画开始前的静态画面也应有完整主体。
""".trimIndent()

private val CANDY_PROMPT = """
一只黑色袋子里有苹果、桃子、西瓜三种口味的糖果，糖果有圆形和五角星形两种形状，两种形状可以靠手感区分。各口味与形状的数量如下：

| 形状 | 苹果味 | 桃子味 | 西瓜味 |
| --- | --- | --- | --- |
| 圆形 | 7 | 9 | 8 |
| 五角星形 | 7 | 6 | 4 |

活动开始前先决定一共取出多少颗糖果。最少取出多少颗，才能保证手中一定同时拥有“不同形状的苹果味和桃子味糖果”（即：圆形苹果味 + 五角星形桃子味，或五角星形苹果味 + 圆形桃子味）？

请只返回一个 JSON 对象，不要输出 HTML、Markdown 代码围栏或任何额外文字。格式如下：
{"answer": <整数>, "reason": "<简要推导过程>"}

要求：
1. answer 必须是整数（替换 <整数> 占位符），表示最少需要取出的糖果颗数。
2. reason 用中文简要说明推导过程。
3. 只输出 JSON 本身，不要输出页面、脚本、样式或额外说明。
""".trimIndent()
