package dev.soven.prismllm.plugin.iq

import dev.soven.prismllm.proxy.ManxueHtmlClassifier
import dev.soven.prismllm.spi.iq.IqJudge
import dev.soven.prismllm.spi.iq.IqJudgeRequest
import dev.soven.prismllm.spi.iq.IqJudgeResult
import dev.soven.prismllm.spi.iq.IqJudgeVerdict
import kotlinx.coroutines.runBlocking

/** 满血AI HTML 分类判分器；只接收 HTML，不接触渠道凭据。 */
class ManxueIqJudge(
    private val classifier: ManxueHtmlClassifier,
) : IqJudge {
    override val id: String = "manxue"
    override val displayName: String = "满血AI HTML 分类"
    override val description: String = "把生成的 HTML 发送到 manxue.ai 分类。"

    override fun judge(request: IqJudgeRequest): IqJudgeResult {
        val assessment = runBlocking { classifier.classify(request.html) }
        return when (assessment.quality) {
            "normal" -> IqJudgeResult(
                verdict = IqJudgeVerdict.PASSED,
                label = assessment.label,
                reason = assessment.reason,
                score = 100.0,
                details = buildMap {
                    put("quality", assessment.quality)
                    assessment.source?.let { put("source", it) }
                },
            )
            "degraded" -> IqJudgeResult(
                verdict = IqJudgeVerdict.INCORRECT,
                label = assessment.label,
                reason = assessment.reason,
                score = 0.0,
                details = buildMap {
                    put("quality", assessment.quality)
                    assessment.source?.let { put("source", it) }
                },
            )
            else -> IqJudgeResult(
                verdict = IqJudgeVerdict.REVIEW,
                label = assessment.label,
                reason = assessment.reason,
                score = null,
                details = buildMap {
                    put("quality", assessment.quality)
                    assessment.source?.let { put("source", it) }
                },
            )
        }
    }
}
