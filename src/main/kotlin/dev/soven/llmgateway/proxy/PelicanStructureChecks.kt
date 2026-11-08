package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.spi.iq.IqStructureCheck
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 鹈鹕作品的离线结构检查。
 *
 * 只做不需要看画面就能判断的事，判据落在「结果」上而不是实现手法上：
 * 不管作者用 SMIL、CSS 还是别的写法，只要结构上没有出现已知的失真就放行。
 * 需要肉眼确认的关系（跨骑遮挡、比例、美感）交给视觉判分或管理员，这里最多给出提示。
 */
internal object PelicanStructureChecks {

    fun analyze(html: String): List<IqStructureCheck> = listOf(
        singleScene(html),
        layerOrder(
            html = html,
            id = "far-layer-order",
            label = "远侧腿画在自行车之前",
            matchesOwn = nameMatches(FAR_PATTERN),
            matchesOther = nameContains(BIKE_HINTS),
            ownFirst = true,
            missingOwn = "未找到远侧腿标记（data-layer/legend/id/class 含 far），需结合画面确认跨骑透视",
            missingOther = "未找到自行车部件的标记，需结合画面确认跨骑透视",
            reversed = "远侧肢体画在自行车之后，会被车轮车架盖住",
        ),
        layerOrder(
            html = html,
            id = "near-layer-order",
            label = "近侧腿画在鹈鹕之后",
            matchesOwn = nameMatches(NEAR_PATTERN),
            matchesOther = nameContains(RIDER_HINTS),
            ownFirst = false,
            missingOwn = "未找到近侧腿标记（data-layer/legend/id/class 含 near），需结合画面确认跨骑透视",
            missingOther = "未找到鹈鹕主体的标记，需结合画面确认跨骑透视",
            reversed = "近侧肢体画在鹈鹕之前，大腿根部会被躯干挡住",
        ),
    )

    /**
     * 场景必须画在同一个 `<svg>` 的坐标系里，否则背景与人车无法锁定比例。
     * 判据是「可见图形不在 SVG 内」：用 `<div class="hill">` 拼背景不行，
     * 但把 SVG 放进 div 里做居中或响应式布局是正常的，不在这里否决。
     */
    private fun singleScene(html: String): IqStructureCheck {
        val document = parse(html)
        // 嵌套 <svg> 仍在同一坐标系里，只统计顶层 SVG。
        val roots = document.select("svg").filter { svg -> svg.parents().none { parent -> parent.tagName() == "svg" } }
        // 只查 SVG 之外的 HTML 块：<foreignObject> 里的内容仍在 SVG 坐标系内。
        val stylesheets = document.select("style").map { it.data() }
        val backgroundBlocks = document.select(BACKGROUND_BLOCK_TAGS)
            .filter { element -> element.parents().none { parent -> parent.tagName() == "svg" } }
            .filter { element ->
                element.classNames().any { className -> BACKGROUND_NAMES_PATTERN.containsMatchIn(className) } &&
                    drawsContent(element, stylesheets)
            }
        return IqStructureCheck(
            id = "single-scene",
            label = "场景绘制在同一 SVG 内",
            passed = roots.size == 1 && backgroundBlocks.isEmpty(),
            detail = when {
                roots.isEmpty() -> "未找到 <svg> 元素"
                roots.size > 1 -> "检测到 ${roots.size} 个独立 <svg>，场景被拆成多个坐标系"
                backgroundBlocks.isNotEmpty() ->
                    "检测到用 HTML 块拼的背景：${backgroundBlocks.take(3).joinToString { describe(it) }}"
                else -> null
            },
        )
    }

    /**
     * 比较两层图元在 DOM 中的先后。只有显式标记时才能离线确认；
     * 没打标记的作品无法判断，记为提示项而不是否决——判定依据是「能不能证明排错了」，
     * 不是「有没有按某种方式起名字」。
     */
    private fun layerOrder(
        html: String,
        id: String,
        label: String,
        matchesOwn: (String) -> Boolean,
        matchesOther: (String) -> Boolean,
        ownFirst: Boolean,
        missingOwn: String,
        missingOther: String,
        reversed: String,
    ): IqStructureCheck {
        // 只比直接绘制的图元：<defs>/<symbol> 里的定义和 <use> 实例的位置不代表实际绘制顺序。
        val elements = parse(html).getAllElements()
            .filterNot { element -> element.parents().any { parent -> parent.tagName() in NON_RENDERED_TAGS } }
        val ownIndexes = elements.indices.filter { index -> matchesLayer(elements[index], matchesOwn) }
        val otherIndexes = elements.indices.filter { index -> matchesLayer(elements[index], matchesOther) }
        if (ownIndexes.isEmpty()) return advisory(id, label, missingOwn)
        if (otherIndexes.isEmpty()) return advisory(id, label, missingOther)
        // 两侧只要有一个图元与对面互为祖孙容器（例如「bicycle > far-leg」），比较的是容器自身，无法判断顺序。
        if (ownIndexes.any { own -> otherIndexes.any { other -> isNested(elements[own], elements[other]) } }) {
            return advisory(id, label, "两侧图元互相嵌套，无法比较绘制顺序，需结合画面确认跨骑透视")
        }
        // 文档顺序就是绘制顺序，靠后的图元盖住靠前的。
        // 远侧要求整体画在自行车之前；近侧要求整体画在鹈鹕之后，所以看的是最靠前的近侧图元与最靠后的鹈鹕图元。
        val boundary = if (ownFirst) {
            ownIndexes.last() to otherIndexes.first()
        } else {
            ownIndexes.first() to otherIndexes.last()
        }
        val passed = (boundary.first < boundary.second) == ownFirst
        val other = elements[boundary.second]
        return IqStructureCheck(
            id = id,
            label = label,
            passed = passed,
            detail = when {
                !passed -> reversed
                ownFirst -> "已排在「${describe(other)}」之前"
                else -> "已排在「${describe(other)}」之后"
            },
        )
    }

    /** 无法离线确认的项：参与展示但不否决。 */
    private fun advisory(id: String, label: String, detail: String) =
        IqStructureCheck(id = id, label = label, passed = true, detail = detail, decisive = false)

    /**
     * 块里是否有 SVG 之外的可见内容：文字、图片、自带背景的块，或没有子 SVG 的块（可能靠 CSS 画背景）。
     * 背景可能来自内联 `style`，也可能来自文档里的 `<style>` 规则，两种都算。
     */
    private fun drawsContent(block: Element, stylesheets: List<String>): Boolean {
        if (block.ownText().isNotBlank() || block.select("img, video, canvas, picture").isNotEmpty()) return true
        if (block.hasAttr("style") && BACKGROUND_STYLE_PATTERN.containsMatchIn(block.attr("style"))) return true
        if (block.classNames().any { className -> stylesheets.any { sheet -> paintsBackground(sheet, className) } }) return true
        return block.children().none { child -> child.tagName() == "svg" || child.select("svg").isNotEmpty() }
    }

    /** 样式表里是否有选中该类名、并声明了背景的规则；不解析层叠关系，只做保守命中。 */
    private fun paintsBackground(sheet: String, className: String): Boolean =
        RULE_PATTERN.findAll(sheet).any { rule ->
            rule.groupValues[1].contains(".$className") && BACKGROUND_STYLE_PATTERN.containsMatchIn(rule.groupValues[2])
        }

    /** 两个元素是否互为祖孙：嵌套时比较的是容器自身，不能代表子图元的绘制顺序。 */
    private fun isNested(one: Element, other: Element): Boolean =
        one.parents().any { it === other } || other.parents().any { it === one }

    /** 元素自身的名字（标注属性、id、class）是否命中某一层的写法。 */
    private fun matchesLayer(element: Element, matches: (String) -> Boolean): Boolean =
        when {
            element.tagName() in IGNORED_TAGS -> false
            LAYER_ATTRS.any { attr -> element.hasAttr(attr) && matches(normalize(element.attr(attr))) } -> true
            matches(normalize(element.id())) -> true
            else -> element.classNames().any { className -> matches(normalize(className)) }
        }

    private fun nameMatches(pattern: Regex): (String) -> Boolean =
        { name -> pattern.containsMatchIn(name) }

    private fun nameContains(hints: List<String>): (String) -> Boolean =
        { name -> hints.any(name::contains) }

    private fun describe(element: Element): String {
        val name = element.id().takeIf { it.isNotEmpty() }
            ?: element.classNames().firstOrNull()
            ?: element.tagName()
        return "<${element.tagName()}> $name"
    }

    private fun parse(html: String): Document =
        Jsoup.parse(html.replace(Regex("(?is)<!DOCTYPE[^>]*>"), ""))

    /** `far_leg`、`farLeg` 统一成 `far-leg`，让常见命名都能命中。 */
    private fun normalize(value: String): String =
        value.replace("_", "-").replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase()

    private val IGNORED_TAGS = setOf("#root", "html", "head", "body")

    /** 只存放定义、不直接绘制内容的容器。 */
    private val NON_RENDERED_TAGS = setOf("defs", "symbol", "clipPath", "mask", "pattern", "marker")

    /** 常见分层属性：`data-layer="far-leg"`、`data-part="near-foot"` 都算显式标记。 */
    private val LAYER_ATTRS = listOf("data-layer", "data-part", "data-name")

    /** 常见背景命名；命中这些 class 的 HTML 块说明背景画在了 SVG 外面。 */
    private val BACKGROUND_NAMES = "sun|cloud|hill|mountain|road|ground|sky|sea|ocean|island|bush|tree"

    /** 只有这些块级容器承载的图形才会落在 SVG 坐标系之外。 */
    private const val BACKGROUND_BLOCK_TAGS = "div, section, main, header"

    /** 内联样式里的背景声明；有背景铺满整个块时，块本身就在绘制背景。 */
    private val BACKGROUND_STYLE_PATTERN = Regex("(?i)\\bbackground(?:-color|-image)?\\s*:")

    /** 粗切样式表规则：选择器与声明块。 */
    private val RULE_PATTERN = Regex("([^{}]+)\\{([^{}]*)\\}")

    /** 按单词匹配：`cloudy` 命中，`mushroom` 这类只有子串相同的命名不会误判。 */
    private val BACKGROUND_NAMES_PATTERN =
        Regex("(?:^|-)(?:$BACKGROUND_NAMES)-?\\d*(?:-|$)", RegexOption.IGNORE_CASE)

    /**
     * 远侧/近侧的写法很多，先归一化成 `far-leg` / `near-leg` 再匹配，避免 `farm` 这类只是包含子串的命名误判。
     * 必须同时带方位词和腿脚部件词：`back-wheel`、`front-fork` 是自行车部件，不能当成腿。
     */
    private val FAR_PATTERN = sidePattern("far|back")
    private val NEAR_PATTERN = sidePattern("near|front")

    private fun sidePattern(side: String): Regex =
        Regex("(?<=^|-)(?:$side)(?=-|$).*(?:^|-)(?:$LEG_WORDS)(?=-|$)")

    private const val LEG_WORDS = "leg|legs|thigh|foot|feet|ankle|shin|limb"

    private val BIKE_HINTS = listOf("bicycle", "bike", "frame", "wheel", "crank", "chain")
    private val RIDER_HINTS = listOf("bird", "rider", "pelican", "torso")
}
