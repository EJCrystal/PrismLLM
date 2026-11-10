package dev.soven.prismllm.proxy.codeassist

import kotlin.test.Test
import kotlin.test.assertEquals

/** Antigravity 模型家族与思考等级变体的映射。 */
class AntigravityModelsTest {
    private val raw = listOf(
        AntigravityModels.Model("gemini-3.7-flash", "Gemini 3.7 Flash"),
        AntigravityModels.Model("gemini-3.7-flash-low", "Gemini 3.7 Flash (Low)"),
        AntigravityModels.Model("gemini-3.7-flash-medium", "Gemini 3.7 Flash (Medium)"),
        AntigravityModels.Model("gemini-3.7-flash-high", "Gemini 3.7 Flash (High)"),
        AntigravityModels.Model("gemini-2.5-pro", "Gemini 2.5 Pro"),
    )

    @Test
    fun splitsIdsAndNames() {
        assertEquals("gemini-3.7-flash" to "high", AntigravityModels.splitId("gemini-3.7-flash-high"))
        assertEquals("gemini-3.7-flash" to "minimal", AntigravityModels.splitId("gemini-3.7-flash-extra-low"))
        assertEquals("gemini-3.7-flash" to "", AntigravityModels.splitId("gemini-3.7-flash"))
        assertEquals("Gemini 3.7 Flash" to "medium", AntigravityModels.nameLevel("Gemini 3.7 Flash (Medium)"))
    }

    @Test
    fun collapsesFamiliesToOneModel() {
        val collapsed = AntigravityModels.collapse(raw)
        assertEquals(listOf("gemini-3.7-flash", "gemini-2.5-pro"), collapsed.map { it.id })
        assertEquals("Gemini 3.7 Flash", collapsed.first().name)
    }

    @Test
    fun picksTheVariantForTheRequestedLevel() {
        assertEquals("gemini-3.7-flash-high", AntigravityModels.sentId("gemini-3.7-flash", "high", raw))
        assertEquals("gemini-3.7-flash-medium", AntigravityModels.sentId("gemini-3.7-flash", "medium", raw))
        assertEquals("gemini-3.7-flash-low", AntigravityModels.sentId("gemini-3.7-flash", "minimal", raw))
        // 没有 xhigh 就用最近的高档
        assertEquals("gemini-3.7-flash-high", AntigravityModels.sentId("gemini-3.7-flash", "max", raw))
        // 不给等级时用家族本身的 id
        assertEquals("gemini-3.7-flash", AntigravityModels.sentId("gemini-3.7-flash", null, raw))
    }

    @Test
    fun keepsVariantIdsAndSingleModels() {
        assertEquals("gemini-3.7-flash-medium", AntigravityModels.sentId("gemini-3.7-flash-medium", null, raw))
        assertEquals("gemini-3.7-flash-high", AntigravityModels.sentId("gemini-3.7-flash-medium", "high", raw))
        assertEquals("gemini-2.5-pro", AntigravityModels.sentId("gemini-2.5-pro", "high", raw))
    }

    @Test
    fun fallsBackToIdLevelsWhenNamesCarryNoLevel() {
        // 名称互不相同但不含等级括号：不能据名称判定等级，应回退到 id 后缀。
        val unnamed = listOf(
            AntigravityModels.Model("foo-low", "Foo Fast"),
            AntigravityModels.Model("foo-high", "Foo Smart"),
        )

        assertEquals("foo-high", AntigravityModels.sentId("foo", "high", unnamed))
        assertEquals("foo-low", AntigravityModels.sentId("foo", "low", unnamed))
        // 名称本身不带等级时原样发布，不要凭空生成家族名。
        assertEquals(listOf("foo"), AntigravityModels.collapse(unnamed).map { it.id })
    }

    @Test
    fun singleVariantFamiliesArePublishedAndMappedByTheirOwnId() {
        val single = listOf(AntigravityModels.Model("gemini-3.7-flash-high", "Gemini 3.7 Flash (High)"))

        // 只有原始 id 可用时按目录原样发布，客户端拿到的就是真实存在的模型。
        assertEquals(listOf("gemini-3.7-flash-high"), AntigravityModels.collapse(single).map { it.id })
        assertEquals(
            "gemini-3.7-flash-high",
            AntigravityModels.sentId("gemini-3.7-flash-high", null, single),
        )
        assertEquals(
            "gemini-3.7-flash-high",
            AntigravityModels.sentId("gemini-3.7-flash-high", "low", single),
        )
        // 即使客户端仍按家族名请求，也能落到唯一变体而不是不存在的 id。
        assertEquals("gemini-3.7-flash-high", AntigravityModels.sentId("gemini-3.7-flash", "high", single))
        assertEquals("gemini-3.7-flash-high", AntigravityModels.sentId("gemini-3.7-flash", null, single))
    }
}
