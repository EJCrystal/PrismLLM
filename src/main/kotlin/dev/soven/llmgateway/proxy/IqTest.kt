package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.spi.iq.IqJudgeResult
import dev.soven.llmgateway.spi.iq.IqJudgeVerdict
import dev.soven.llmgateway.spi.iq.IqStructureCheck
import dev.soven.llmgateway.spi.iq.IqStructureSummary
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.Normalizer

/**
 * 智商检测的内置题目。
 *
 * 两个题目对应当前社区常用的“鹈鹕骑行”和“糖果测试”。每道题都带一份内置提示词，
 * 管理员可以在运行时替换提示词，便于调整题目细节；历史记录会保留当次实际使用的提示词。
 *
 * 判分器可插拔：鹈鹕由运行时选中的 IqJudge 判定；
 * 糖果测试固定使用满血官方规则：回答可见文字中出现独立的 21 即通过。
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
        summary = "生成单文件 HTML，在页面中给出糖果组合推理题的最终答案与推理过程。",
        grading = "回答可见文字中出现独立的 21（或“二十一”）即通过；选中的判分器结果仅作参考。",
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
    val visibleText = extractVisibleText(text)
    val normalized = Normalizer.normalize(visibleText, Normalizer.Form.NFKC)
    val matched = containsStandaloneNumber(normalized, CANDY_EXPECTED_ANSWER) ||
        CANDY_CHINESE_ANSWER_PATTERN.containsMatchIn(normalized)
    val checks = listOf(
        IqStructureCheck(
            id = "answer",
            label = "最终答案包含 $CANDY_EXPECTED_ANSWER",
            passed = matched,
            detail = if (matched) {
                "找到独立答案 $CANDY_EXPECTED_ANSWER"
            } else {
                "未找到独立答案 $CANDY_EXPECTED_ANSWER（或“二十一”）"
            },
        ),
    )
    return IqTestEvaluation(
        verdict = if (matched) IqVerdict.PASSED else IqVerdict.INCORRECT,
        checks = checks,
        html = extractHtmlDocument(text),
        expectedAnswer = CANDY_EXPECTED_ANSWER,
        matchedAnswer = if (matched) CANDY_EXPECTED_ANSWER else null,
    )
}

private fun IqJudgeVerdict.toIqVerdict(): IqVerdict = when (this) {
    IqJudgeVerdict.PASSED -> IqVerdict.PASSED
    IqJudgeVerdict.INCORRECT -> IqVerdict.INCORRECT
    IqJudgeVerdict.REVIEW -> IqVerdict.REVIEW
    // 判分器自身失败（超时、插件异常等）不等于模型作品失败，交给人工确认。
    IqJudgeVerdict.FAILED -> IqVerdict.REVIEW
}

internal fun containsStandaloneNumber(text: String, number: Int): Boolean =
    Regex("(?<!\\d)$number(?!\\d)").containsMatchIn(text)

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

/**
 * 提取 HTML 的可见文字，避免把 CSS 里的 `21px` 或颜色值当成糖果答案。
 * 纯文本响应按原样返回。
 */
internal fun extractVisibleText(raw: String): String {
    val html = extractHtmlDocument(raw) ?: return raw
    val document = Jsoup.parse(html)
    val rules = parseVisibilityRules(document)
    document.select("script, style, template").remove()
    val body = document.body() ?: return ""
    // 从最深层开始删除，jsoup 的 remove() 会带走整棵隐藏子树，嵌套同名标签不会漏掉。
    body.getAllElements().asReversed().forEach { element ->
        if (element !== body && isElementHidden(element, rules)) element.remove()
    }
    return body.text().trim()
}

private fun parseVisibilityRules(document: Document): List<VisibilityRule> {
    val rules = mutableListOf<VisibilityRule>()
    var order = 0
    document.select("style").forEach { style ->
        CSS_RULE_PATTERN.findAll(style.data()).forEach { match ->
            val declarations = parseVisibilityDeclarations(match.groupValues[2])
            if (declarations.isNotEmpty()) {
                val ruleOrder = order++
                splitCssSelectors(match.groupValues[1]).forEach { selector ->
                    rules += VisibilityRule(selector, selectorSpecificity(selector), ruleOrder, declarations)
                }
            }
        }
    }
    return rules
}

private fun isElementHidden(element: Element, rules: List<VisibilityRule>): Boolean {
    var display: CssVisibilityValue? = null
    var visibility: CssVisibilityValue? = null
    fun apply(declaration: VisibilityDeclaration, specificity: Int, order: Int) {
        val value = CssVisibilityValue(declaration.hidden, declaration.important, specificity, order)
        when (declaration.property) {
            "display" -> if (value.beats(display)) display = value
            "visibility" -> if (value.beats(visibility)) visibility = value
        }
    }
    rules.forEach { rule ->
        if (element.matchesSelector(rule.selector)) {
            rule.declarations.forEach { apply(it, rule.specificity, rule.order) }
        }
    }
    // 内联样式优先级高于样式表；!important 的比较在 CssVisibilityValue 内统一处理。
    parseVisibilityDeclarations(element.attr("style")).forEach {
        apply(it, INLINE_STYLE_SPECIFICITY, Int.MAX_VALUE)
    }
    if (display?.hidden == true || visibility?.hidden == true) return true
    // hidden 属性等价于 UA 的 display:none，只有显式 display:block 等声明能覆盖它。
    return element.hasAttr("hidden") && display?.hidden != false
}

private fun Element.matchesSelector(selector: String): Boolean =
    try {
        `is`(selector)
    } catch (_: Exception) {
        false
    }

private fun splitCssSelectors(selectorList: String): List<String> {
    val selectors = mutableListOf<String>()
    val current = StringBuilder()
    var depth = 0
    selectorList.forEach { char ->
        when (char) {
            '(', '[' -> {
                depth += 1
                current.append(char)
            }
            ')', ']' -> {
                if (depth > 0) depth -= 1
                current.append(char)
            }
            ',' -> if (depth == 0) {
                current.toString().trim().takeIf(String::isNotEmpty)?.let(selectors::add)
                current.clear()
            } else {
                current.append(char)
            }
            else -> current.append(char)
        }
    }
    current.toString().trim().takeIf(String::isNotEmpty)?.let(selectors::add)
    return selectors
}

/** 只用于 display/visibility 的层叠比较，按 ID、类、元素三档近似计算优先级。 */
private fun selectorSpecificity(selector: String): Int {
    val normalized = selector.replace(Regex("\"[^\"]*\"|'[^']*'"), "")
    val ids = Regex("#[\\w-]+").findAll(normalized).count()
    val classes = Regex("\\.[\\w-]+|\\[[^]]*]|:(?!:)[\\w-]+").findAll(normalized).count()
    val elements = Regex("(?:^|[\\s>+~])([a-zA-Z][\\w-]*)").findAll(normalized).count()
    return ids * 100 + classes * 10 + elements
}

private fun parseVisibilityDeclarations(block: String): List<VisibilityDeclaration> =
    CSS_DECLARATION_PATTERN.findAll(block).mapNotNull { match ->
        val property = match.groupValues[1].lowercase()
        if (property != "display" && property != "visibility") return@mapNotNull null
        val rawValue = match.groupValues[2]
        val important = IMPORTANT_DECLARATION_PATTERN.containsMatchIn(rawValue)
        val value = IMPORTANT_DECLARATION_PATTERN.replace(rawValue, "").trim().lowercase()
        VisibilityDeclaration(
            property = property,
            hidden = when (property) {
                "display" -> value == "none"
                else -> value == "hidden" || value == "collapse"
            },
            important = important,
        )
    }.toList()

private data class VisibilityRule(
    val selector: String,
    val specificity: Int,
    val order: Int,
    val declarations: List<VisibilityDeclaration>,
)

private data class VisibilityDeclaration(
    val property: String,
    val hidden: Boolean,
    val important: Boolean,
)

private data class CssVisibilityValue(
    val hidden: Boolean,
    val important: Boolean,
    val specificity: Int,
    val order: Int,
) {
    fun beats(other: CssVisibilityValue?): Boolean {
        if (other == null) return true
        if (important != other.important) return important
        if (specificity != other.specificity) return specificity > other.specificity
        return order >= other.order
    }
}

private const val INLINE_STYLE_SPECIFICITY = 1_000

private const val CANDY_EXPECTED_ANSWER = 21
private val CANDY_CHINESE_ANSWER_PATTERN = Regex(
    "(?<![\\d〇零一二三四五六七八九十百千万亿两廿])(?:二十一|廿一)" +
        "(?![\\d〇零一二三四五六七八九十百千万亿两廿])",
)

private val CSS_RULE_PATTERN = Regex("(?is)([^{}]+)\\{([^{}]*)\\}")
private val CSS_DECLARATION_PATTERN = Regex("(?i)([a-z-]+)\\s*:\\s*([^;{}]+)")
private val IMPORTANT_DECLARATION_PATTERN = Regex("(?i)!\\s*important")

private const val HTML_CLOSE = "</html>"
private const val BODY_CLOSE = "</body>"
private const val SVG_CLOSE = "</svg>"

private const val PREVIEW_STYLE =
    "<style>html,body{margin:0;min-height:100%;display:grid;place-items:center;background:#fff}svg{max-width:100%;height:auto}</style>"

private val HTML_FENCE_PATTERN = Regex("```(?:html)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)

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

请生成一个可直接运行的单文件 HTML 页面，在页面中用清晰的中文给出最终答案和简要推理过程。

要求：
1. 只输出一份完整 HTML，不要 Markdown 代码围栏、解释、候选方案或额外说明。
2. 页面不依赖任何外部图片、字体、脚本、样式表或网络资源。
3. 最终答案要作为可见文字出现，并明确写出“最少取出多少颗”。
""".trimIndent()
