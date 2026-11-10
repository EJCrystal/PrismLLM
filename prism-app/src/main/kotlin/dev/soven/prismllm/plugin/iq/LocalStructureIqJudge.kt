package dev.soven.prismllm.plugin.iq

import dev.soven.prismllm.spi.iq.IqJudge
import dev.soven.prismllm.spi.iq.IqJudgeRequest
import dev.soven.prismllm.spi.iq.IqJudgeResult
import dev.soven.prismllm.spi.iq.IqJudgeVerdict

/** 本地结构判分：不访问任何外部服务，结构通过后等待人工评分。 */
class LocalStructureIqJudge : IqJudge {
    override val id: String = "local"
    override val displayName: String = "本地结构 + 人工"
    override val description: String = "不访问外部服务，结构检查后由管理员评分。"

    override fun judge(request: IqJudgeRequest): IqJudgeResult {
        // 糖果题由 AdminRoutes 直接读取 JSON 答案，不会走到判分器。
        val structure = request.structure
        return when {
            !structure.htmlPresent || !structure.svgPresent -> IqJudgeResult(
                verdict = IqJudgeVerdict.FAILED,
                label = "未通过",
                reason = "未生成可解析的 HTML/SVG。",
                score = 0.0,
                details = mapOf("mode" to "structure"),
            )
            structure.allDecisivePassed -> IqJudgeResult(
                verdict = IqJudgeVerdict.REVIEW,
                label = "结构通过，待人工评分",
                reason = "本地结构检查全部通过；请结合预览人工确认鹈鹕与自行车是否可辨。",
                score = null,
                details = mapOf("mode" to "structure"),
            )
            else -> IqJudgeResult(
                verdict = IqJudgeVerdict.INCORRECT,
                label = "结构未通过",
                reason = "本地结构检查未全部通过。",
                score = 0.0,
                details = mapOf("mode" to "structure"),
            )
        }
    }
}
