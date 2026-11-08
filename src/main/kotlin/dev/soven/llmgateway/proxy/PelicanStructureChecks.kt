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
        val backgroundBlocks = document.select(BACKGROUND_BLOCK_TAGS).filter { element ->
            element.classNames().any { className -> BACKGROUND_NAMES_PATTERN.containsMatchIn(className) }
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
        val elements = parse(html).getAllElements()
        // 某一侧可能由多个图元组成，按整体比较：own 全部画完、other 才开始画，才算排对。
        val lastOwn = elements.indexOfLast { element -> matchesLayer(element, matchesOwn) }
        val firstOther = elements.indexOfFirst { element -> matchesLayer(element, matchesOther) }
        if (lastOwn < 0) return advisory(id, label, missingOwn)
        if (firstOther < 0) return advisory(id, label, missingOther)
        val other = elements[firstOther]
        // 两侧互为祖孙容器时（例如「bicycle > far-leg」），比较的是容器自身，不代表子图元的绘制顺序。
        if (isNested(elements[lastOwn], other)) {
            return advisory(id, label, "两侧图元互相嵌套，无法比较绘制顺序，需结合画面确认跨骑透视")
        }
        // 文档顺序就是绘制顺序，靠后的图元盖住靠前的：[ownFirst] 为真表示 own 应整体画在 other 之前。
        val passed = (lastOwn < firstOther) == ownFirst
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

    /** 常见分层属性：`data-layer="far-leg"`、`data-part="near-foot"` 都算显式标记。 */
    private val LAYER_ATTRS = listOf("data-layer", "data-part", "data-name")

    /** 常见背景命名；命中这些 class 的 HTML 块说明背景画在了 SVG 外面。 */
    private val BACKGROUND_NAMES = "sun|cloud|hill|mountain|road|ground|sky|sea|ocean|island|bush|tree"

    /** 只有这些块级容器承载的图形才会落在 SVG 坐标系之外。 */
    private const val BACKGROUND_BLOCK_TAGS = "div, section, main, header"

    /** 按单词匹配：`cloudy` 命中，`mushroom` 这类只有子串相同的命名不会误判。 */
    private val BACKGROUND_NAMES_PATTERN =
        Regex("(?:^|-)(?:$BACKGROUND_NAMES)-?\\d*(?:-|$)", RegexOption.IGNORE_CASE)

    /**
     * 远侧/近侧的写法很多，先归一化成 `far-leg` / `near-leg`，再按整词匹配：
     * `farLeg`、`back-foot` 命中，`farm`、`dwarf` 这类只是包含子串的命名不会误判。
     */
    private val FAR_PATTERN = Regex("(?<=^|-)(?:far|back)(?=-|$)")
    private val NEAR_PATTERN = Regex("(?<=^|-)(?:near|front)(?=-|$)")

    private val BIKE_HINTS = listOf("bicycle", "bike", "frame", "wheel", "crank", "chain")
    private val RIDER_HINTS = listOf("bird", "rider", "pelican", "torso")
}
