package dev.soven.llmgateway.proxy

sealed interface SvgRenderResult {
    data class Success(val png: ByteArray, val width: Int, val height: Int) : SvgRenderResult
    data class Failure(val reason: String) : SvgRenderResult
}

/** SVG 中声明的动画类型与大致周期，用于选择多帧采样的时间点。 */
data class SvgAnimationInfo(
    val durationSeconds: Float,
    val hasSmil: Boolean,
    val hasCss: Boolean,
)

/** 渲染器接口，便于测试时替换真实截图实现。 */
interface HtmlImageRenderer {
    /** 展示在判分详情里的渲染器名称。 */
    val label: String get() = "SVG"

    /** 渲染器是否可用；不可用时视觉判分直接转人工确认。 */
    val available: Boolean get() = true

    fun render(html: String): SvgRenderResult

    /**
     * 按给定时间点渲染多帧。默认实现退化为同一帧，方便测试替身；
     * 真实渲染器会为每个时间点单独截图。
     */
    fun renderFrames(html: String, times: List<Float>): List<SvgRenderResult> = times.map { render(html) }
}

/**
 * SVG 清洗与尺寸解析的共享实现。
 *
 * 无头浏览器渲染前会统一使用这套规则，移除脚本、外部资源和事件属性，
 * 避免模型输出触发网络请求或执行代码。
 */
internal object SvgRenderSupport {
    private const val EXTERNAL_URL = "(?:(?:https?|file|ftp):(?://)?|//)"
    private const val SCRIPT_URL = "(?:javascript|vbscript|data:text/html):"

    private val scriptBlock = Regex("(?is)<script\\b[^>]*>.*?</script>|<script\\b[^>]*/>")
    private val framedBlock = Regex("(?is)<(?:iframe|object|embed)\\b[^>]*>.*?</(?:iframe|object|embed)>")
    private val tagToken = Regex("""(?is)<(/?)([a-z][\w:.-]*)((?:"[^"]*"|'[^']*'|[^'">])*?)(/?)>""")
    private val attributeToken = Regex("""([^\s=/>]+)(?:\s*=\s*("[^"]*"|'[^']*'|[^\s>]*))?""")
    private val cssImport = Regex("(?is)@import\\s+(?:url\\()?\\s*[\"']?[^;]+;")
    private val externalCssUrl = Regex("(?is)url\\(\\s*[\"']?\\s*$EXTERNAL_URL[^)]*\\)")
    private val scriptCssUrl = Regex("(?is)url\\(\\s*[\"']?\\s*$SCRIPT_URL[^)]*\\)")

    fun sanitizeSvg(svg: String): String {
        var value = svg
            .replace(Regex("(?is)<!DOCTYPE[^>]*>"), "")
            .replace(Regex("(?is)<!ENTITY[^>]*>"), "")
            .replace(scriptBlock, "")
            .replace(framedBlock, "")
            // 逐标签重建属性，避免“删除 href 后把两个属性拼成新标签名”的绕过。
            .replace(tagToken, ::sanitizeTag)
            .replace(cssImport, "")
            .replace(externalCssUrl, "none")
            .replace(scriptCssUrl, "none")
        if (!Regex("(?is)<svg\\b[^>]*\\bxmlns\\s*=").containsMatchIn(value)) {
            value = value.replaceFirst(Regex("(?is)<svg\\b"), "<svg xmlns=\"http://www.w3.org/2000/svg\"")
        }
        return value
    }

    private fun sanitizeTag(match: MatchResult): String {
        val closing = match.groupValues[1].isNotEmpty()
        val name = match.groupValues[2]
        val normalized = name.lowercase()
        if (normalized in BLOCKED_TAGS) return ""
        if (closing) return "</$name>"
        var droppedResource = false
        val kept = parseAttributes(match.groupValues[3]).filter { attribute ->
            val attributeName = attribute.name.lowercase()
            when {
                // 浏览器会调度 onload；内联事件属性一律移除。
                attributeName.startsWith("on") -> false
                attributeName in UNSAFE_ATTRIBUTES -> false
                attributeName in RESOURCE_ATTRIBUTES && !isSafeResource(attribute.value) -> {
                    droppedResource = true
                    false
                }
                else -> true
            }
        }
        // 引用外部地址的图片/复用元素整体移除，避免渲染器尝试加载远程或本地资源。
        if (droppedResource && normalized in EXTERNAL_REFERENCE_ELEMENTS) return ""
        val attributes = kept.joinToString("") { renderAttribute(it) }
        return if (match.groupValues[4].isNotEmpty()) "<$name$attributes/>" else "<$name$attributes>"
    }

    private fun parseAttributes(raw: String): List<TagAttribute> =
        attributeToken.findAll(raw).mapNotNull { match ->
            val name = match.groupValues[1]
            if (name.isBlank() || name.startsWith("/")) {
                null
            } else {
                TagAttribute(name, match.groups[2]?.value)
            }
        }.toList()

    private fun renderAttribute(attribute: TagAttribute): String {
        val raw = attribute.value ?: return " ${attribute.name}"
        val value = when {
            raw.length >= 2 && raw.first() == '"' && raw.last() == '"' -> raw.substring(1, raw.length - 1)
            raw.length >= 2 && raw.first() == '\'' && raw.last() == '\'' -> raw.substring(1, raw.length - 1)
            else -> raw
        }
        val safeValue = if (attribute.name.equals("style", ignoreCase = true)) sanitizeCss(value) else value
        return " ${attribute.name}=\"${safeValue.replace("\"", "&quot;").replace("<", "&lt;")}\""
    }

    private fun isSafeResource(raw: String?): Boolean {
        if (raw == null) return true
        val value = raw.trim().trim('"', '\'').trim().lowercase()
        // 只允许文档内引用和图片数据；相对地址也可能落到网关本机。
        return value.isEmpty() || value.startsWith("#") || value.startsWith("data:image/")
    }

    private data class TagAttribute(val name: String, val value: String?)

    private val BLOCKED_TAGS = setOf("script", "iframe", "object", "embed", "link", "base", "meta")
    private val UNSAFE_ATTRIBUTES =
        setOf("srcdoc", "http-equiv", "formaction", "action", "target", "ping", "srcset")
    private val RESOURCE_ATTRIBUTES = setOf("href", "xlink:href", "src", "poster", "background")
    private val EXTERNAL_REFERENCE_ELEMENTS = setOf("image", "use")

    fun sanitizeCss(css: String): String = css
        // CSS 转义后的 `<` 仍然表示字符本身，但不可能再拼出 </style> 逃逸样式元素。
        .replace("<", "\\3C ")
        .replace(cssImport, "")
        .replace(externalCssUrl, "none")
        .replace(scriptCssUrl, "none")

    /** 读取 SVG 的 viewBox/宽高，返回期望的像素宽度与高度。 */
    fun svgSize(svg: String, widthCap: Int): Pair<Int, Int> {
        val tag = Regex("(?is)<svg\\b[^>]*>").find(svg)?.value.orEmpty()
        val viewBox = Regex("(?is)\\bviewBox\\s*=\\s*[\"']([^\"']+)[\"']").find(tag)?.groupValues?.get(1)
            ?.split(Regex("[\\s,]+"))?.mapNotNull(String::toFloatOrNull)
        if (viewBox != null && viewBox.size == 4 && viewBox[2] > 0f && viewBox[3] > 0f) {
            return scaled(viewBox[2], viewBox[3], widthCap)
        }
        val width = attributePx(tag, "width")
        val height = attributePx(tag, "height")
        if (width != null && height != null && width > 0f && height > 0f) {
            return scaled(width, height, widthCap)
        }
        return 800 to 600
    }

    private fun scaled(width: Float, height: Float, widthCap: Int): Pair<Int, Int> {
        val scale = widthCap.toFloat() / width
        return widthCap to (height * scale).toInt().coerceIn(120, widthCap)
    }

    private fun attributePx(tag: String, name: String): Float? {
        val raw = Regex("(?is)\\b$name\\s*=\\s*[\"']([^\"']+)[\"']").find(tag)?.groupValues?.get(1) ?: return null
        return Regex("[-+]?\\d+(?:\\.\\d+)?").find(raw)?.value?.toFloatOrNull()
    }
}

/** 从完整 HTML 中截取第一段完整的内联 SVG；没有闭合标签时返回 null。 */
internal fun extractInlineSvg(html: String?): String? {
    if (html.isNullOrBlank()) return null
    var depth = 0
    var start = -1
    for (match in SVG_TAG_PATTERN.findAll(html)) {
        if (match.groupValues[1].isEmpty()) {
            if (depth == 0) start = match.range.first
            depth += 1
            // 外层的自闭合 <svg/> 也要能正常返回。
            if (match.value.trimEnd().endsWith("/>")) depth -= 1
        } else if (depth > 0) {
            depth -= 1
        }
        if (depth == 0 && start >= 0) {
            return html.substring(start, match.range.last + 1)
        }
    }
    return null
}

/**
 * 根据 SVG/HTML 中的动画声明估算采样周期。
 *
 * SMIL 的 `dur` 和 CSS 的 `animation` / `animation-duration` 都会被识别；多个动画取最大周期，
 * 没有声明时默认 2 秒。结果只用于选择截图时间点，不参与判分结论。
 */
internal fun analyzeSvgAnimation(html: String?): SvgAnimationInfo {
    val raw = html.orEmpty()
    val durations = animationDurations(raw)
    val duration = durations.maxOrNull()?.coerceIn(0.4f, 10f) ?: DEFAULT_ANIMATION_SECONDS
    return SvgAnimationInfo(
        durationSeconds = duration,
        hasSmil = SMIL_ANIMATION_PATTERN.containsMatchIn(raw),
        hasCss = CSS_ANIMATION_PATTERN.containsMatchIn(raw),
    )
}

/** 在一个动画周期内均匀取 count 个时间点，第一帧从 0 秒开始。 */
internal fun samplingTimes(info: SvgAnimationInfo, count: Int): List<Float> {
    val frames = count.coerceAtLeast(1)
    if (frames == 1) return listOf(0f)
    val step = info.durationSeconds / frames
    // 与常见整数周期错开，避免短周期动画在每个采样点都落在同一相位。
    val jitter = step * GOLDEN_RATIO_FRACTION / frames
    return (0 until frames).map { it * (step + jitter) }
}

private fun animationDurations(raw: String): List<Float> = buildList {
    SMIL_DURATION_PATTERN.findAll(raw).forEach { addDuration(it) }
    CSS_DURATION_PATTERN.findAll(raw).forEach { addDuration(it) }
}

private fun MutableList<Float>.addDuration(match: MatchResult) {
    val value = match.groupValues.getOrNull(1)?.toFloatOrNull() ?: return
    val unit = match.groupValues.getOrNull(2)?.lowercase().orEmpty()
    add(if (unit == "ms") value / 1000f else value)
}

private const val DEFAULT_ANIMATION_SECONDS = 2f
private const val GOLDEN_RATIO_FRACTION = 0.381966f

/** 多帧截图会一起发送给视觉模型，限制单帧宽度以控制请求体积。 */
internal const val MULTI_FRAME_WIDTH = 640

private val SVG_TAG_PATTERN = Regex("<(/?)(svg)(?=[\\s/>])[^>]*>", RegexOption.IGNORE_CASE)
private val SMIL_ANIMATION_PATTERN = Regex("<animate(?:Transform|Motion|Color)?\\b", RegexOption.IGNORE_CASE)
private val CSS_ANIMATION_PATTERN = Regex("@keyframes\\b|animation\\s*:", RegexOption.IGNORE_CASE)
private val SMIL_DURATION_PATTERN = Regex(
    "\\bdur\\s*=\\s*[\"']\\s*([0-9.]+)\\s*(ms|s)\\s*[\"']",
    RegexOption.IGNORE_CASE,
)
private val CSS_DURATION_PATTERN = Regex(
    "animation(?:-duration)?\\s*:\\s*[^;{}]*?([0-9.]+)\\s*(ms|s)\\b",
    RegexOption.IGNORE_CASE,
)
