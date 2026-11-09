package dev.soven.llmgateway.proxy.codeassist

import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals

/** fetchAvailableModels 的解析与过滤：禁用、内部与隐藏模型都不进目录。 */
class CodeAssistModelCatalogTest {
    private val catalog = CodeAssistModelCatalog(HttpClient())

    @Test
    fun parsesChatModelsAndSkipsDisabledInternalAndHidden() {
        val body = """
            {"models":{
              "gemini-3.7-flash-high":{"displayName":"Gemini 3.7 Flash (High)","maxTokens":128000,"maxOutputTokens":8192,"supportsImages":true},
              "gemini-3.7-flash-low":{"displayName":"Gemini 3.7 Flash (Low)"},
              "gemini-4-argon-disabled":{"displayName":"Gemini 4 Argon","disabled":true},
              "chat_20706":{"displayName":"internal"},
              "tab_flash_lite_preview":{"displayName":"tab"},
              "gemini-2.5-pro":{"displayName":"Gemini 2.5 Pro"}
            }}
        """.trimIndent()

        val models = catalog.parseModels(body)

        assertEquals(listOf("gemini-3.7-flash-high", "gemini-3.7-flash-low"), models.map { it.id })
        assertEquals("Gemini 3.7 Flash (High)", models.first().name)
        assertEquals(128000L, models.first().context)
        assertEquals(true, models.first().imageInput)
    }

    @Test
    fun returnsEmptyForMalformedBodies() {
        assertEquals(emptyList(), catalog.parseModels("not json"))
        assertEquals(emptyList(), catalog.parseModels("{}"))
    }
}
