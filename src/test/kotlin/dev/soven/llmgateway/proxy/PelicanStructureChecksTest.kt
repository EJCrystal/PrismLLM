package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.spi.iq.IqStructureCheck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 验证鹈鹕作品的离线结构检查：只否决能用结构证明的失真，没打标记的项退化为提示。 */
class PelicanStructureChecksTest {

    @Test
    fun `single svg with nested svg counts as one scene`() {
        val check = check(analyze("<svg><g><svg><circle r=\"1\" /></svg></g></svg>"), "single-scene")

        assertTrue(check.passed, check.detail)
        assertTrue(check.decisive)
    }

    @Test
    fun `multiple root svg fails single scene`() {
        val check = check(analyze("<svg id=\"a\"></svg><svg id=\"b\"></svg>"), "single-scene")

        assertFalse(check.passed)
        assertTrue(check.detail.orEmpty().contains("2 个独立"))
    }

    @Test
    fun `html background blocks fail single scene but svg wrapper is fine`() {
        val wrapped = check(
            analyze("<!DOCTYPE html><html><body><div class=\"stage\"><svg id=\"a\"></svg></div></body></html>"),
            "single-scene",
        )
        assertTrue(wrapped.passed, wrapped.detail)

        // 走完整 HTML 文档：只有 SVG 片段时外层 div 会被预览提取逻辑丢掉，检查自然也看不到。
        val split = check(
            analyze(
                "<!DOCTYPE html><html><body><div class=\"sky\"></div><div class='hill'></div>" +
                    "<svg id=\"a\"></svg></body></html>",
            ),
            "single-scene",
        )
        assertFalse(split.passed)
        assertEquals("single-scene", split.id)
    }

    @Test
    fun `far leg before bike passes`() {
        val check = check(
            analyze("<svg><g class=\"far-leg\"></g><g class=\"wheel\"></g><g class=\"near-leg\"></g></svg>"),
            "far-layer-order",
        )

        assertTrue(check.passed)
        assertTrue(check.decisive)
    }

    @Test
    fun `far leg after bike fails`() {
        val check = check(
            analyze("<svg><g class=\"frame\"></g><g class=\"far_leg\"></g></svg>"),
            "far-layer-order",
        )

        assertFalse(check.passed)
        assertTrue(check.decisive)
    }

    @Test
    fun `near leg after rider passes`() {
        val check = check(
            // 近侧腿必须在鹈鹕之后：否则后画出来的躯干会盖住大腿根部。
            analyze("<svg><g id=\"pelican-body\"></g><g id=\"nearLeg\"></g></svg>"),
            "near-layer-order",
        )

        assertTrue(check.passed)
        assertTrue(check.decisive)
    }

    @Test
    fun `near leg before rider fails and data-layer marks work`() {
        val reversed = check(
            analyze("<svg><g data-layer=\"near-leg\"></g><g id=\"rider\"></g></svg>"),
            "near-layer-order",
        )
        assertFalse(reversed.passed)
        assertTrue(reversed.decisive)

        val ordered = check(
            analyze("<svg><g id=\"pelican-body\"></g><g data-layer=\"near-leg\"></g></svg>"),
            "near-layer-order",
        )
        assertTrue(ordered.passed)
        assertTrue(ordered.decisive)
    }

    @Test
    fun `unlabelled layers are advisory and do not fail`() {
        val checks = analyze("<svg><circle class=\"wheel\" /></svg>")

        val far = check(checks, "far-layer-order")
        val near = check(checks, "near-layer-order")
        assertTrue(far.passed)
        assertFalse(far.decisive)
        assertTrue(near.passed)
        assertFalse(near.decisive)
        assertNotNull(far.detail)
    }

    @Test
    fun `nested groups are advisory instead of failing`() {
        val checks = analyze(
            "<svg><g id=\"bicycle\"><g id=\"far-leg\"></g><g id=\"wheel\"></g></g></svg>",
        )

        val far = check(checks, "far-layer-order")
        assertTrue(far.passed)
        assertFalse(far.decisive)
        assertNotNull(far.detail)
    }

    @Test
    fun `nested rider group does not decide near order`() {
        val html = """
            <!DOCTYPE html>
            <html><head><style>@keyframes spin{to{transform:rotate(360deg)}}</style></head>
            <body><svg><g id="pelican"><g id="near-leg"></g></g></svg></body></html>
        """.trimIndent()

        val result = evaluateIqTest(IqBenchmark.PELICAN, true, html)

        assertEquals(IqVerdict.PASSED, result.verdict)
        assertFalse(result.checks.first { it.id == "near-layer-order" }.decisive)
    }

    @Test
    fun `scattered near parts still decide against the whole rider`() {
        val check = check(
            // 大腿画在躯干之前，脚画在躯干之后：近侧应整体在鹈鹕之后，这里仍算排反。
            analyze("<svg><g id=\"near-thigh\"></g><g id=\"pelican-body\"></g><g id=\"near-foot\"></g></svg>"),
            "near-layer-order",
        )

        assertFalse(check.passed)
        assertTrue(check.decisive)
    }

    @Test
    fun `definitions do not count as drawing order`() {
        val check = check(
            analyze(
                "<svg><defs><g id=\"bike-frame\"></g><g id=\"far-leg\"></g></defs>" +
                    "<use href=\"#far-leg\"/><use href=\"#bike-frame\"/></svg>",
            ),
            "far-layer-order",
        )

        // 定义顺序（bike 在前）不代表绘制顺序，应降为提示而不是判失败。
        assertTrue(check.passed)
        assertFalse(check.decisive)
    }

    @Test
    fun `background named layout wrapper is not a background`() {
        val check = check(
            analyze("<!DOCTYPE html><html><body><div class=\"sky\"><svg id=\"a\"></svg></div></body></html>"),
            "single-scene",
        )

        assertTrue(check.passed, check.detail)
        assertTrue(check.decisive)

        // 同一个容器自己画了背景，背景就落在 SVG 坐标系之外。
        val painted = check(
            analyze(
                "<!DOCTYPE html><html><body>" +
                    "<div class=\"sky\" style=\"background: linear-gradient(#fff, #9cf)\"><svg id=\"a\"></svg></div>" +
                    "</body></html>",
            ),
            "single-scene",
        )
        assertFalse(painted.passed)
    }

    @Test
    fun `background painted from stylesheet still fails single scene`() {
        val check = check(
            analyze(
                "<!DOCTYPE html><html><head><style>.sky{background:linear-gradient(#fff,#9cf)}</style></head>" +
                    "<body><div class=\"sky\"><svg id=\"a\"></svg></div></body></html>",
            ),
            "single-scene",
        )

        assertFalse(check.passed)
    }

    @Test
    fun `similar class names and descendant selectors do not count as background`() {
        val similar = check(
            analyze(
                "<!DOCTYPE html><html><head><style>.skyline{background:blue}</style></head>" +
                    "<body><div class=\"sky\"><svg id=\"a\"></svg></div></body></html>",
            ),
            "single-scene",
        )
        assertTrue(similar.passed, similar.detail)

        val descendant = check(
            analyze(
                "<!DOCTYPE html><html><head><style>.sky .cloud{background:blue}</style></head>" +
                    "<body><div class=\"sky\"><svg id=\"a\"></svg></div></body></html>",
            ),
            "single-scene",
        )
        assertTrue(descendant.passed, descendant.detail)
    }

    @Test
    fun `non-rendered containers are excluded from layer comparison`() {
        val check = check(
            analyze(
                "<svg><symbol id=\"bike-frame\"><path d=\"M0 0h10\"/></symbol>" +
                    "<path id=\"far-leg\" d=\"M0 0v10\"/><use href=\"#bike-frame\"/></svg>",
            ),
            "far-layer-order",
        )

        // symbol 自身不是绘制节点，远侧腿之后并没有真正的自行车图元，只能看画面。
        assertTrue(check.passed)
        assertFalse(check.decisive)
    }

    @Test
    fun `foreignObject background stays inside the scene`() {
        val check = check(
            analyze(
                "<svg><foreignObject><div class=\"sky\" style=\"background:blue\"></div></foreignObject></svg>",
            ),
            "single-scene",
        )

        assertTrue(check.passed, check.detail)
        assertTrue(check.decisive)
    }

    @Test
    fun `side words on bike parts are not legs`() {
        val far = check(
            analyze("<svg><g class=\"far-leg\"></g><g class=\"back-wheel\"></g><g class=\"frame\"></g></svg>"),
            "far-layer-order",
        )
        assertTrue(far.passed, far.detail)
        assertTrue(far.decisive)

        val near = check(
            analyze("<svg><g class=\"pelican-body\"></g><g class=\"front-wheel\"></g><g class=\"near-foot\"></g></svg>"),
            "near-layer-order",
        )
        assertTrue(near.passed, near.detail)
        assertTrue(near.decisive)
    }

    @Test
    fun `both sides in one scene only match their own side`() {
        val far = check(
            analyze(
                "<svg><g class=\"far-leg\"></g><g class=\"bike-frame\"></g>" +
                    "<g class=\"pelican-body\"></g><g class=\"near-foot\"></g></svg>",
            ),
            "far-layer-order",
        )
        assertTrue(far.passed, far.detail)
        assertTrue(far.decisive)

        val near = check(
            analyze(
                "<svg><g class=\"far-leg\"></g><g class=\"bike-frame\"></g>" +
                    "<g class=\"pelican-body\"></g><g class=\"near-foot\"></g></svg>",
            ),
            "near-layer-order",
        )
        assertTrue(near.passed, near.detail)
        assertTrue(near.decisive)
    }

    @Test
    fun `reversed layers need review instead of passing`() {
        val html = """
            <!DOCTYPE html>
            <html><head><style>@keyframes spin{to{transform:rotate(360deg)}}</style></head>
            <body><svg><g class="bike-frame"></g><g class="far-leg"></g></svg></body></html>
        """.trimIndent()

        val result = evaluateIqTest(IqBenchmark.PELICAN, true, html)

        assertEquals(IqVerdict.REVIEW, result.verdict)
    }

    @Test
    fun `candy checks are all decisive`() {
        val result = evaluateIqTest(IqBenchmark.CANDY, true, """{"answer": 21, "reason": "穷举"}""")

        assertTrue(result.checks.all { it.decisive })
    }

    /** 和真实调用链一致：先按预览规则提取 HTML 文档，再跑结构检查。 */
    private fun analyze(html: String): List<IqStructureCheck> =
        analyzePelicanStructure(extractHtmlDocument(html)).checks

    private fun check(checks: List<IqStructureCheck>, id: String): IqStructureCheck =
        checks.first { it.id == id }
}
