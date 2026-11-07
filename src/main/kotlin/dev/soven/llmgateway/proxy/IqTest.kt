package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.spi.iq.IqJudgeResult
import dev.soven.llmgateway.spi.iq.IqJudgeVerdict
import dev.soven.llmgateway.spi.iq.IqStructureCheck
import dev.soven.llmgateway.spi.iq.IqStructureSummary
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
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
    val visible = extractVisibleTextResult(text)
    if (visible.uncertain) {
        // 媒体条件无法在离线判分时求值，答案是否可见交给人工确认。
        return IqTestEvaluation(
            verdict = IqVerdict.REVIEW,
            checks = listOf(
                IqStructureCheck(
                    id = "answer",
                    label = "最终答案包含 $CANDY_EXPECTED_ANSWER",
                    passed = false,
                    detail = "页面包含无法在离线判分时求值的媒体条件，请人工确认答案是否可见",
                ),
            ),
            html = extractHtmlDocument(text),
            expectedAnswer = CANDY_EXPECTED_ANSWER,
        )
    }
    val normalized = Normalizer.normalize(visible.text, Normalizer.Form.NFKC)
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
internal fun extractVisibleText(raw: String): String = extractVisibleTextResult(raw).text

private fun extractVisibleTextResult(raw: String): VisibleText {
    val html = extractHtmlDocument(raw) ?: return VisibleText(raw)
    val document = Jsoup.parse(html)
    val analysis = parseVisibilityRules(document)
    document.select("script, style, template").remove()
    val body = document.body() ?: return VisibleText("", analysis.uncertain)
    var uncertain = analysis.uncertain
    val states = HashMap<Element, ElementVisibility>()
    document.getAllElements().forEach { element ->
        // display/visibility 由 CSS 变量决定时无法离线求值，转人工确认。
        if (DYNAMIC_VISIBILITY_PATTERN.containsMatchIn(element.attr("style"))) uncertain = true
        states[element] = evaluateVisibility(element, analysis.rules)
    }
    // html 根元素上的 display/visibility 同样会隐藏整个页面，需要参与继承。
    val root = document.selectFirst("html") ?: body
    val rootState = states[root] ?: ElementVisibility(false, null)
    if (rootState.displayHidden) return VisibleText("", uncertain)
    return VisibleText(
        visibleText(body, states, rootState.visibilityHidden ?: false),
        uncertain,
    )
}

/** 可见文字，以及是否需要人工确认（页面包含离线判分无法求值的媒体条件）。 */
private data class VisibleText(val text: String, val uncertain: Boolean = false)

private data class VisibilityAnalysis(
    val rules: List<VisibilityRule>,
    val uncertain: Boolean,
)

private fun parseVisibilityRules(document: Document): VisibilityAnalysis {
    val rules = mutableListOf<VisibilityRule>()
    var order = 0
    var uncertain = false
    document.select("style").forEach { style ->
        // <style media="print"> 只对打印生效，不能当作屏幕样式参与判分。
        val media = style.attr("media").trim()
        when (if (media.isEmpty()) true else mediaApplies(media)) {
            false -> return@forEach
            null -> {
                if (affectsVisibility(style.data())) uncertain = true
                return@forEach
            }
            true -> Unit
        }
        val screen = screenApplicableCss(style.data())
        uncertain = uncertain || screen.uncertain ||
            DYNAMIC_VISIBILITY_PATTERN.containsMatchIn(screen.css)
        CSS_RULE_PATTERN.findAll(screen.css).forEach { match ->
            val declarations = parseVisibilityDeclarations(match.groupValues[2])
            if (declarations.isNotEmpty()) {
                val ruleOrder = order++
                splitCssSelectors(match.groupValues[1]).forEach { selector ->
                    rules += VisibilityRule(selector, specificityOf(selector), ruleOrder, declarations)
                }
            }
        }
    }
    return VisibilityAnalysis(rules, uncertain)
}

/**
 * 去掉当前预览环境不生效的 at-rule 块，并按 1024×768 的桌面预览求值媒体查询。
 *
 * 直接对整段 CSS 做正则会把 `@media print{p{display:none}}` 里的声明当成屏幕样式。
 * 这里按大括号配对提取 at-rule：媒体查询为真时把内部规则提升到顶层参与层叠；
 * 为假时整块忽略；包含无法离线求值的媒体特性且会影响可见性时标记
 * [ScreenCss.uncertain]，由调用方转人工确认，而不是猜测。
 */
private fun screenApplicableCss(css: String): ScreenCss {
    val source = CSS_COMMENT_PATTERN.replace(css, "")
    val out = StringBuilder(source.length)
    var uncertain = false
    var i = 0
    while (i < source.length) {
        when (source[i]) {
            // 顶层普通规则整块复制，块内的 @ 字符（如 content 字符串）不参与判断。
            '{' -> {
                val end = matchingBrace(source, i)
                out.append(source, i, end + 1)
                i = end + 1
            }
            '@' -> {
                val semicolon = source.indexOf(';', i)
                val brace = source.indexOf('{', i)
                if (brace < 0 || (semicolon in 0 until brace)) {
                    // @import / @charset 等语句型规则整条丢弃。
                    i = if (semicolon >= 0) semicolon + 1 else source.length
                    continue
                }
                val end = matchingBrace(source, brace)
                val prelude = source.substring(i, brace).trim().lowercase()
                val inner = source.substring(brace + 1, end)
                when {
                    prelude == "@media" || prelude.startsWith("@media ") -> {
                        when (mediaApplies(prelude.removePrefix("@media").trim())) {
                            true -> {
                                val nested = screenApplicableCss(inner)
                                out.append(nested.css)
                                uncertain = uncertain || nested.uncertain
                            }
                            false -> Unit
                            // 无法求值的条件只有在会影响 display/visibility 时才需要人工确认。
                            null -> if (affectsVisibility(inner)) uncertain = true
                        }
                    }
                    // 层叠层的优先级无法在离线判分时完整复现；影响可见性时转人工确认。
                    prelude == "@layer" || prelude.startsWith("@layer ") -> {
                        val nested = screenApplicableCss(inner)
                        uncertain = uncertain || nested.uncertain || affectsVisibility(nested.css)
                    }
                    // @supports 的条件同样无法离线求值，会影响可见性时转人工确认。
                    prelude.startsWith("@supports") -> if (affectsVisibility(inner)) uncertain = true
                    else -> Unit
                }
                i = end + 1
            }
            else -> {
                out.append(source[i])
                i += 1
            }
        }
    }
    return ScreenCss(out.toString(), uncertain)
}

private data class ScreenCss(val css: String, val uncertain: Boolean)

private fun affectsVisibility(css: String): Boolean =
    VISIBILITY_DECLARATION_PATTERN.containsMatchIn(css)

/** 返回 null 表示该媒体查询无法在离线判分时求值。 */
private fun mediaApplies(query: String): Boolean? {
    var unknown = false
    for (part in query.split(',')) {
        when (singleMediaApplies(part.trim())) {
            true -> return true
            null -> unknown = true
            false -> Unit
        }
    }
    return if (unknown) null else false
}

private fun singleMediaApplies(query: String): Boolean? {
    if (query.isEmpty()) return true
    var text = query
    var negated = false
    when {
        text.startsWith("not ", ignoreCase = true) -> {
            negated = true
            text = text.substring(4).trim()
        }
        text.startsWith("only ", ignoreCase = true) -> text = text.substring(5).trim()
    }
    var result: Boolean? = true
    for (part in text.split(Regex("(?i)\\s+and\\s+"))) {
        val value = mediaPartApplies(part.trim().lowercase())
        result = when {
            result == false || value == false -> false
            result == null || value == null -> null
            else -> true
        }
        if (result == false) break
    }
    return if (negated) result?.not() else result
}

/** 已知媒体类型按预览环境（屏幕）判定；其他类型和未知特性返回 null。 */
private fun mediaPartApplies(part: String): Boolean? = when {
    part == "screen" || part == "all" -> true
    part in OTHER_MEDIA_TYPES -> false
    part.startsWith("(") -> mediaFeatureApplies(part)
    else -> null
}

/** 按固定预览视口求值宽度、高度和方向特性；其余特性交给人工确认。 */
private fun mediaFeatureApplies(raw: String): Boolean? {
    val feature = raw.trim().removePrefix("(").removeSuffix(")").trim()
    val separator = feature.indexOf(':')
    val name = if (separator >= 0) feature.substring(0, separator).trim() else feature
    val value = if (separator >= 0) feature.substring(separator + 1).trim() else null
    return when (name) {
        "width" -> mediaLengthPx(value)?.let { PREVIEW_VIEWPORT_WIDTH.toFloat() == it }
        "min-width" -> mediaLengthPx(value)?.let { PREVIEW_VIEWPORT_WIDTH >= it }
        "max-width" -> mediaLengthPx(value)?.let { PREVIEW_VIEWPORT_WIDTH <= it }
        "height" -> mediaLengthPx(value)?.let { PREVIEW_VIEWPORT_HEIGHT.toFloat() == it }
        "min-height" -> mediaLengthPx(value)?.let { PREVIEW_VIEWPORT_HEIGHT >= it }
        "max-height" -> mediaLengthPx(value)?.let { PREVIEW_VIEWPORT_HEIGHT <= it }
        "orientation" -> when (value) {
            "landscape" -> true
            "portrait" -> false
            else -> null
        }
        else -> null
    }
}

/** 解析媒体特性里的长度；只支持与像素可换算的单位，其他单位交给人工确认。 */
private fun mediaLengthPx(value: String?): Float? {
    if (value == null) return null
    val match = MEDIA_LENGTH_PATTERN.find(value) ?: return null
    val number = match.groupValues[1].toFloatOrNull() ?: return null
    return when (match.groupValues[2]) {
        "", "px" -> number
        "em", "rem" -> number * 16f
        else -> null
    }
}

/** 返回与 `{` 配对的 `}` 下标；配对失败时返回字符串末尾。 */
private fun matchingBrace(source: String, open: Int): Int {
    var depth = 1
    var quote: Char? = null
    var i = open + 1
    while (i < source.length) {
        val char = source[i]
        when {
            quote != null -> if (char == quote) quote = null
            char == '"' || char == '\'' -> quote = char
            char == '{' -> depth += 1
            char == '}' -> {
                depth -= 1
                if (depth == 0) return i
            }
        }
        i += 1
    }
    return source.length - 1
}

private fun evaluateVisibility(element: Element, rules: List<VisibilityRule>): ElementVisibility {
    var display: CssVisibilityValue? = null
    var visibility: CssVisibilityValue? = null
    fun apply(declaration: VisibilityDeclaration, specificity: Specificity, order: Int, inline: Boolean) {
        val value = CssVisibilityValue(declaration.hidden, declaration.important, inline, specificity, order)
        when (declaration.property) {
            "display" -> if (value.beats(display)) display = value
            "visibility" -> if (value.beats(visibility)) visibility = value
        }
    }
    rules.forEach { rule ->
        if (element.matchesSelector(rule.selector)) {
            rule.declarations.forEach { apply(it, rule.specificity, rule.order, inline = false) }
        }
    }
    // 内联样式优先级高于任何选择器；!important 的比较在 CssVisibilityValue 内统一处理。
    parseVisibilityDeclarations(element.attr("style")).forEach {
        apply(it, INLINE_STYLE_SPECIFICITY, Int.MAX_VALUE, inline = true)
    }
    // hidden 属性等价于 UA 的 display:none，只有显式 display:block 等声明能覆盖它。
    val displayHidden = display?.hidden == true ||
        (element.hasAttr("hidden") && display?.hidden != false)
    return ElementVisibility(displayHidden, visibility?.hidden)
}

/**
 * 遍历 DOM 取可见文字。display:none 会隐藏整棵子树；visibility 可被后代显式覆盖，
 * 因此只跳过继承到 hidden 的文本节点，保留 `visibility:visible` 的子元素。
 */
private fun visibleText(
    root: Element,
    states: Map<Element, ElementVisibility>,
    inheritedVisibilityHidden: Boolean,
): String {
    val builder = StringBuilder()
    fun visit(node: Node, visibilityHiddenFromParent: Boolean) {
        when (node) {
            is TextNode -> if (!visibilityHiddenFromParent) builder.append(node.wholeText)
            is Element -> {
                val state = states[node] ?: ElementVisibility(false, null)
                if (state.displayHidden) return
                if (node.normalName() == "br") {
                    builder.append(' ')
                    return
                }
                val visibilityHidden = state.visibilityHidden ?: visibilityHiddenFromParent
                val block = node.isBlock
                if (block) builder.append(' ')
                if (node.normalName() == "details" && !node.hasAttr("open")) {
                    // 未展开的 details 只渲染第一个 summary，其余内容处于折叠状态。
                    node.children().firstOrNull { it.normalName() == "summary" }
                        ?.let { visit(it, visibilityHidden) }
                } else {
                    node.childNodes().forEach { child -> visit(child, visibilityHidden) }
                }
                if (block) builder.append(' ')
            }
        }
    }
    visit(root, inheritedVisibilityHidden)
    return builder.toString().replace(Regex("\\s+"), " ").trim()
}

private data class ElementVisibility(
    val displayHidden: Boolean,
    /** null 表示没有显式 visibility 声明，继承父元素。 */
    val visibilityHidden: Boolean?,
)

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

/** 按 ID、类、元素三档逐项比较；不能用十进制进位，否则 10 个类会超过 1 个 ID。 */
private data class Specificity(val ids: Int, val classes: Int, val elements: Int) :
    Comparable<Specificity> {
    override fun compareTo(other: Specificity): Int =
        compareValuesBy(this, other, { it.ids }, { it.classes }, { it.elements })
}

/**
 * 按选择器语法计算三档优先级。
 *
 * 函数式伪类不能只看名字：`:where()` 本身为 0，`:not()` / `:is()` / `:has()`
 * 只取参数中最高的一项，其余伪类仍按类计算。直接数 `:not(` 会高估这类选择器，
 * 让本该被后续规则覆盖的隐藏声明胜出。
 */
private fun specificityOf(selector: String): Specificity {
    var ids = 0
    var classes = 0
    var elements = 0
    var expectElement = true
    var i = 0
    while (i < selector.length) {
        when (val char = selector[i]) {
            '#' -> {
                ids += 1
                i = skipIdentifier(selector, i + 1)
                expectElement = false
            }
            '.' -> {
                classes += 1
                i = skipIdentifier(selector, i + 1)
                expectElement = false
            }
            '[' -> {
                classes += 1
                i = skipBracket(selector, i)
                expectElement = false
            }
            ':' -> {
                if (selector.getOrNull(i + 1) == ':') {
                    elements += 1
                    i = skipIdentifier(selector, i + 2)
                    expectElement = false
                } else {
                    val nameEnd = skipIdentifier(selector, i + 1)
                    val name = selector.substring(i + 1, nameEnd).lowercase()
                    if (selector.getOrNull(nameEnd) == '(') {
                        val close = matchingParen(selector, nameEnd)
                        val args = selector.substring(nameEnd + 1, close)
                        val argument = when (name) {
                            "where" -> Specificity(0, 0, 0)
                            "not", "is", "has" -> strongestSpecificity(args)
                            else -> {
                                classes += 1
                                if (name == "nth-child" || name == "nth-last-child") {
                                    val of = Regex("(?i)\\bof\\b").find(args)
                                    if (of != null) {
                                        strongestSpecificity(args.substring(of.range.last + 1))
                                    } else {
                                        Specificity(0, 0, 0)
                                    }
                                } else {
                                    Specificity(0, 0, 0)
                                }
                            }
                        }
                        ids += argument.ids
                        classes += argument.classes
                        elements += argument.elements
                        i = close + 1
                        expectElement = false
                    } else {
                        classes += 1
                        i = nameEnd
                        expectElement = false
                    }
                }
            }
            in 'a'..'z', in 'A'..'Z', '_' -> {
                if (expectElement) elements += 1
                i = skipIdentifier(selector, i)
                expectElement = false
            }
            '>', '+', '~', ',', ')' -> {
                expectElement = true
                i += 1
            }
            '*' -> {
                expectElement = false
                i += 1
            }
            else -> {
                if (char.isWhitespace()) expectElement = true
                i += 1
            }
        }
    }
    return Specificity(ids, classes, elements)
}

private fun strongestSpecificity(selectorList: String): Specificity =
    splitTopLevel(selectorList)
        .map { specificityOf(it.trim()) }
        .maxWithOrNull(compareBy({ it.ids }, { it.classes }, { it.elements }))
        ?: Specificity(0, 0, 0)

private fun skipIdentifier(source: String, start: Int): Int {
    var i = start
    while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '-' || source[i] == '_')) {
        i += 1
    }
    return i
}

/** 跳过 `[...]` 属性选择器，引号内的 `]` 不算结束。 */
private fun skipBracket(source: String, open: Int): Int {
    var quote: Char? = null
    var i = open + 1
    while (i < source.length) {
        val char = source[i]
        when {
            quote != null -> if (char == quote) quote = null
            char == '"' || char == '\'' -> quote = char
            char == ']' -> return i + 1
        }
        i += 1
    }
    return source.length
}

/** 跳过括号及其嵌套内容；用于 `:not(...)` 等函数式伪类。 */
private fun matchingParen(source: String, open: Int): Int {
    var depth = 1
    var quote: Char? = null
    var i = open + 1
    while (i < source.length) {
        val char = source[i]
        when {
            quote != null -> if (char == quote) quote = null
            char == '"' || char == '\'' -> quote = char
            char == '(' -> depth += 1
            char == ')' -> {
                depth -= 1
                if (depth == 0) return i
            }
        }
        i += 1
    }
    return source.length - 1
}

/** 按顶层逗号拆分，忽略括号和方括号内的逗号。 */
private fun splitTopLevel(source: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var depth = 0
    var quote: Char? = null
    source.forEach { char ->
        when {
            quote != null -> {
                current.append(char)
                if (char == quote) quote = null
            }
            char == '"' || char == '\'' -> {
                quote = char
                current.append(char)
            }
            char == '(' || char == '[' -> {
                depth += 1
                current.append(char)
            }
            char == ')' || char == ']' -> {
                if (depth > 0) depth -= 1
                current.append(char)
            }
            char == ',' && depth == 0 -> {
                current.toString().trim().takeIf(String::isNotEmpty)?.let(parts::add)
                current.clear()
            }
            else -> current.append(char)
        }
    }
    current.toString().trim().takeIf(String::isNotEmpty)?.let(parts::add)
    return parts
}

private fun parseVisibilityDeclarations(block: String): List<VisibilityDeclaration> =
    CSS_DECLARATION_PATTERN.findAll(CSS_COMMENT_PATTERN.replace(block, "")).mapNotNull { match ->
        val property = match.groupValues[1].lowercase()
        if (property != "display" && property != "visibility") return@mapNotNull null
        val rawValue = match.groupValues[2]
        val important = IMPORTANT_DECLARATION_PATTERN.containsMatchIn(rawValue)
        val value = IMPORTANT_DECLARATION_PATTERN.replace(rawValue, "").trim().lowercase()
        val hidden = when (property) {
            // 浏览器会忽略无效的 display 值，不能让 `display:none;display:invalid` 覆盖前一条声明。
            "display" -> when {
                value == "none" -> true
                // initial/unset/revert/inherit 对普通元素都是可见的默认布局。
                value in DISPLAY_DEFAULT_VALUES -> false
                value.isNotEmpty() && value.split(Regex("\\s+")).all { it in DISPLAY_VISIBLE_VALUES } -> false
                else -> return@mapNotNull null
            }
            else -> when (value) {
                "hidden", "collapse" -> true
                "visible", "initial", "revert", "revert-layer" -> false
                // visibility 是继承属性；inherit/unset 表示跟随父元素。
                "inherit", "unset" -> null
                else -> return@mapNotNull null
            }
        }
        VisibilityDeclaration(property, hidden, important)
    }.toList()

private data class VisibilityRule(
    val selector: String,
    val specificity: Specificity,
    val order: Int,
    val declarations: List<VisibilityDeclaration>,
)

private data class VisibilityDeclaration(
    val property: String,
    /** null 表示 inherit/unset，跟随父元素。 */
    val hidden: Boolean?,
    val important: Boolean,
)

private data class CssVisibilityValue(
    val hidden: Boolean?,
    val important: Boolean,
    val inline: Boolean,
    val specificity: Specificity,
    val order: Int,
) {
    fun beats(other: CssVisibilityValue?): Boolean {
        if (other == null) return true
        if (important != other.important) return important
        // 内联样式优先于任何选择器，但仍低于样式的 !important。
        if (inline != other.inline) return inline
        if (specificity != other.specificity) return specificity > other.specificity
        return order >= other.order
    }
}

private val INLINE_STYLE_SPECIFICITY = Specificity(0, 0, 0)

/** 离线判分按管理台预览的桌面视口求值媒体查询。 */
private const val PREVIEW_VIEWPORT_WIDTH = 1024
private const val PREVIEW_VIEWPORT_HEIGHT = 768

// 满血官方判分规则使用 21（见 docs/iq-test.md）；按组合推理算出的 29 不参与判分，不自行改题。
private const val CANDY_EXPECTED_ANSWER = 21
private val CANDY_CHINESE_ANSWER_PATTERN = Regex(
    "(?<![\\d〇零一二三四五六七八九十百千万亿两廿])(?:二十一|廿一)" +
        "(?![\\d〇零一二三四五六七八九十百千万亿两廿])",
)

private val CSS_COMMENT_PATTERN = Regex("(?s)/\\*.*?\\*/")
private val CSS_RULE_PATTERN = Regex("(?is)([^{}]+)\\{([^{}]*)\\}")
private val CSS_DECLARATION_PATTERN = Regex("(?i)([a-z-]+)\\s*:\\s*([^;{}]+)")
private val IMPORTANT_DECLARATION_PATTERN = Regex("(?i)!\\s*important")
private val VISIBILITY_DECLARATION_PATTERN = Regex("(?i)(?:^|[;{])\\s*(?:display|visibility)\\s*:")
private val DYNAMIC_VISIBILITY_PATTERN =
    Regex("(?i)(?:^|[;{])\\s*(?:display|visibility)\\s*:[^;{}]*\\bvar\\s*\\(")
private val MEDIA_LENGTH_PATTERN = Regex("^([0-9]*\\.?[0-9]+)(px|em|rem)?$")
private val DISPLAY_DEFAULT_VALUES = setOf("initial", "unset", "revert", "revert-layer", "inherit")
private val DISPLAY_VISIBLE_VALUES = setOf(
    "block", "inline", "inline-block", "flow", "flow-root", "flex", "inline-flex", "grid", "inline-grid",
    "table", "inline-table", "table-row-group", "table-header-group", "table-footer-group",
    "table-row", "table-cell", "table-column-group", "table-column", "table-caption",
    "list-item", "contents", "run-in", "ruby", "ruby-base", "ruby-text",
    "ruby-base-container", "ruby-text-container", "math",
)
private val OTHER_MEDIA_TYPES = setOf(
    "print", "speech", "tty", "tv", "projection", "handheld", "braille", "embossed", "aural",
)

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
