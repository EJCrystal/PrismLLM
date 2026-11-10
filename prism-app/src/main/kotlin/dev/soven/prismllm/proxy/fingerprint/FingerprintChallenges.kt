package dev.soven.prismllm.proxy.fingerprint

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import kotlin.random.Random

/**
 * 生成三条“凭第一反应写整数”的提示词。
 *
 * 模板与 lm-detector 的 challenge-browser.js 保持一致：五种语言、多条开场/动作/结尾/分隔语随机组合，
 * 三条题目的数字数量从 292..332 中不重复抽取。参考库按这些环境训练，随意改写会改变数字分布。
 */
internal object FingerprintChallengeGenerator {
    private const val TEMPLATE_RESOURCE = "/fingerprint/prompt_templates.json"

    private val templates: Map<String, FingerprintPromptLanguage> by lazy {
        val text = FingerprintChallengeGenerator::class.java.getResourceAsStream(TEMPLATE_RESOURCE)
            ?.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            ?: error("缺少指纹检测提示词模板 $TEMPLATE_RESOURCE")
        Json.decodeFromString<Map<String, FingerprintPromptLanguage>>(text)
    }

    fun generate(count: Int = 3, random: Random = Random.Default): List<FingerprintChallenge> {
        require(count in 1..41) { "指纹检测题目数量必须在 1..41 之间" }
        require(templates.isNotEmpty()) { "指纹检测提示词模板为空" }
        val availableLengths = (292..332).toMutableList()
        return (0 until count).map { index ->
            val expectedCount = availableLengths.removeAt(random.nextInt(availableLengths.size))
            val language = templates.values.random(random)
            val action = language.actions.random(random)
            val request = language.request
                .replace("__ACTION__", action)
                .replace("__COUNT__", expectedCount.toString())
            val prompt = listOf(
                language.openings.random(random),
                request,
                language.rules,
                language.endings.random(random),
                language.separators.random(random),
                language.start,
            ).joinToString(language.space)
            FingerprintChallenge(index = index, expectedCount = expectedCount, prompt = prompt)
        }
    }
}

@Serializable
internal data class FingerprintPromptLanguage(
    val space: String,
    val openings: List<String>,
    val actions: List<String>,
    val request: String,
    val rules: String,
    val endings: List<String>,
    val separators: List<String>,
    val start: String,
)