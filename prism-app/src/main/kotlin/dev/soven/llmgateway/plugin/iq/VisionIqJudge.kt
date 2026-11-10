package dev.soven.llmgateway.plugin.iq

import dev.soven.llmgateway.admin.domain.IqVisionConfig
import dev.soven.llmgateway.admin.persistence.IqVisionSettingsSnapshot
import dev.soven.llmgateway.admin.persistence.IqVisionSettingsStatus
import dev.soven.llmgateway.proxy.HtmlImageRenderer
import dev.soven.llmgateway.proxy.SvgRenderResult
import dev.soven.llmgateway.proxy.VisionAnimationInfo
import dev.soven.llmgateway.proxy.VisionAspect
import dev.soven.llmgateway.proxy.VisionAspectStatus
import dev.soven.llmgateway.proxy.VisionAssessor
import dev.soven.llmgateway.proxy.VisionFrame
import dev.soven.llmgateway.proxy.VisionRequest
import dev.soven.llmgateway.proxy.analyzeSvgAnimation
import dev.soven.llmgateway.proxy.samplingTimes
import dev.soven.llmgateway.spi.iq.IqJudge
import dev.soven.llmgateway.spi.iq.IqJudgeRequest
import dev.soven.llmgateway.spi.iq.IqJudgeResult
import dev.soven.llmgateway.spi.iq.IqJudgeVerdict
import kotlinx.coroutines.runBlocking

/** 单次判分实际使用的视觉模型；每次判分重新解析，支持管理台改配置后立即生效。 */
sealed interface ActiveVisionModel {
    data class Ready(val assessor: VisionAssessor, val model: String) : ActiveVisionModel

    /** 未启用或端点未配置；判分转人工确认并说明原因。 */
    data class Unavailable(val reason: String) : ActiveVisionModel
}

/**
 * 内置视觉判分器：用无头浏览器把内联 SVG 渲染成多帧截图，再交给配置的多模态模型逐项评分。
 *
 * 无头浏览器同时推进 CSS 和 SMIL 动画；没有可用浏览器时直接返回人工确认，
 * 不降级到其他渲染器。
 *
 * 渲染和模型调用都发生在网关进程内；渠道密钥不会传给判分器。模型端点默认取
 * `IQ_VISION_BASE_URL`，也可以由管理台「系统配置 → 智商检测视觉判分」覆盖。
 */
class VisionIqJudge private constructor(
    private val renderer: HtmlImageRenderer,
    private val frameCount: Int,
    private val activeModel: () -> ActiveVisionModel,
) : IqJudge {
    /** 固定配置：测试替身和不接管理台的部署使用。 */
    constructor(
        renderer: HtmlImageRenderer,
        assessor: VisionAssessor,
        model: String,
        frameCount: Int = VISION_FRAME_COUNT,
    ) : this(renderer, frameCount, { ActiveVisionModel.Ready(assessor, model) })

    /**
     * 动态配置：每次判分读取一次设置，管理台保存后无需重启。
     *
     * @param settings 读取当前生效配置；读库失败时应返回不可用而不是回退到部署默认值。
     * @param assessorFactory 用给定配置构造调用器；端点或密钥变化时自然换新。
     */
    constructor(
        renderer: HtmlImageRenderer,
        settings: () -> IqVisionSettingsSnapshot,
        assessorFactory: (IqVisionConfig) -> VisionAssessor,
        frameCount: Int = VISION_FRAME_COUNT,
    ) : this(renderer, frameCount, {
        val snapshot = settings()
        val config = snapshot.config
        when {
            snapshot.status == IqVisionSettingsStatus.UNREADABLE ->
                ActiveVisionModel.Unavailable("读取视觉判分配置失败，本次跳过视觉判分，请稍后重试。")
            !config.enabled -> ActiveVisionModel.Unavailable("视觉判分已在系统配置中关闭。")
            !config.usable -> ActiveVisionModel.Unavailable("视觉判分端点未配置有效的 http(s) 地址。")
            config.model.isBlank() -> ActiveVisionModel.Unavailable("视觉判分未配置模型名。")
            else -> ActiveVisionModel.Ready(assessorFactory(config), config.model)
        }
    })

    override val id: String = "vision"
    override val displayName: String = "无头浏览器截图 + 视觉模型"
    override val description: String =
        "用无头浏览器逐帧渲染内联 SVG（CSS 与 SMIL 动画），再调用配置的多模态模型检查" +
            "鹈鹕特征、自行车结构、骑行关系、动画连贯性和画面完成度；不发送渠道密钥。" +
            "模型端点可在「系统配置」中调整。"

    override fun judge(request: IqJudgeRequest): IqJudgeResult {
        if (!renderer.available) {
            return IqJudgeResult(
                verdict = IqJudgeVerdict.REVIEW,
                label = "需人工查看",
                reason = "未找到可用的无头浏览器，无法逐帧渲染动画截图。" +
                    "请安装 Chrome/Chromium，或设置 IQ_VISION_BROWSER_PATH 后重试。",
                details = mapOf("渲染器" to renderer.label),
            )
        }
        val active = when (val resolved = activeModel()) {
            is ActiveVisionModel.Ready -> resolved
            is ActiveVisionModel.Unavailable -> return IqJudgeResult(
                verdict = IqJudgeVerdict.REVIEW,
                label = "需人工查看",
                reason = resolved.reason,
                details = mapOf("渲染器" to renderer.label),
            )
        }
        val model = active.model
        val animation = analyzeSvgAnimation(request.html)
        val times = samplingTimes(animation, frameCount)
        val rendered = renderer.renderFrames(request.html, times)
        val frames = ArrayList<VisionFrame>(rendered.size)
        var width = 0
        var height = 0
        rendered.forEachIndexed { index, result ->
            when (result) {
                is SvgRenderResult.Failure -> return renderFailure(result.reason)
                is SvgRenderResult.Success -> {
                    frames += VisionFrame(times.getOrElse(index) { 0f }, result.png)
                    width = result.width
                    height = result.height
                }
            }
        }
        if (frames.isEmpty()) return renderFailure("渲染器没有返回截图")
        val totalBytes = frames.sumOf { it.png.size }
        if (totalBytes > MAX_TOTAL_IMAGE_BYTES) {
            return renderFailure("截图总量超过 ${MAX_TOTAL_IMAGE_BYTES / 1024 / 1024} MiB，无法发送视觉模型")
        }

        val identical = frames.size > 1 && frames.all { it.png.contentEquals(frames.first().png) }
        val assessment = runBlocking {
            active.assessor.assess(
                VisionRequest(
                    frames = frames,
                    benchmark = request.benchmark,
                    structure = request.structure,
                    animation = VisionAnimationInfo(
                        durationSeconds = animation.durationSeconds,
                        frameCount = frames.size,
                        framesIdentical = identical,
                        hasSmil = animation.hasSmil,
                        hasCss = animation.hasCss,
                    ),
                ),
            )
        }
        val aspects = adjustAspects(assessment.aspects, identical).let { adjusted ->
            // 模型没有返回逐项结果时，至少保证“各帧相同”不会被漏判成功。
            if (adjusted.isEmpty() && identical) {
                listOf(
                    VisionAspect(
                        ANIMATION_ASPECT,
                        VisionAspectStatus.FAILED,
                        "六帧截图完全相同，未观察到动画或画面变化。",
                    ),
                )
            } else {
                adjusted
            }
        }
        val verdict = resolveVerdict(aspects, assessment.verdict)
        val reason = if (aspects != assessment.aspects) aspectSummary(aspects) else assessment.reason
        val score = if (verdict == IqJudgeVerdict.INCORRECT) 0.0 else assessment.score
        val details = linkedMapOf<String, String>()
        aspects.forEach { aspect ->
            details[aspect.key] = "${aspect.status.label}：${aspect.detail}"
        }
        if (aspects.isEmpty() && !assessment.reason.isNullOrBlank()) {
            details["检测结果"] = assessment.reason
        }
        details["技术信息"] = buildString {
            append("${renderer.label} · ${frames.size} 帧")
            if (width > 0 && height > 0) append(" · ${width}×${height}")
            append(" · ${totalBytes / 1024} KB")
            append(" · $model")
            if (identical) append(" · 各帧相同")
        }
        return IqJudgeResult(
            verdict = verdict,
            label = label(verdict),
            reason = reason,
            score = score,
            details = details,
        )
    }

    /**
     * 动画连贯性不允许停在 unknown：
     * - 各帧截图完全相同，说明没有可见的动态效果，直接判失败；
     * - 模型自己无法确认连续运动时，同样按失败处理。
     *
     * 其他维度仍可使用 unknown，并最终进入人工确认。
     */
    private fun adjustAspects(
        aspects: List<VisionAspect>,
        framesIdentical: Boolean,
    ): List<VisionAspect> {
        if (aspects.isEmpty()) return aspects
        return aspects.map { aspect ->
            if (aspect.key != ANIMATION_ASPECT) return@map aspect
            when {
                framesIdentical -> aspect.copy(
                    status = VisionAspectStatus.FAILED,
                    detail = "各帧截图完全相同，未观察到动画或画面变化。${aspect.detail}",
                )
                aspect.status == VisionAspectStatus.UNKNOWN -> aspect.copy(
                    status = VisionAspectStatus.FAILED,
                    detail = "无法确认连续运动，按未通过处理。${aspect.detail}",
                )
                else -> aspect
            }
        }
    }

    private fun aspectSummary(aspects: List<VisionAspect>): String = buildString {
        val failed = aspects.filter { it.status == VisionAspectStatus.FAILED }.map { it.key }
        val unknown = aspects.filter { it.status == VisionAspectStatus.UNKNOWN }.map { it.key }
        if (failed.isNotEmpty()) append("未通过：${failed.joinToString("、")}。")
        if (unknown.isNotEmpty()) append("无法确认：${unknown.joinToString("、")}。")
        append("其余项目见逐项检测。")
    }

    private fun resolveVerdict(aspects: List<VisionAspect>, fallback: IqJudgeVerdict): IqJudgeVerdict = when {
        aspects.any { it.status == VisionAspectStatus.FAILED } -> IqJudgeVerdict.INCORRECT
        fallback == IqJudgeVerdict.INCORRECT -> IqJudgeVerdict.INCORRECT
        aspects.any { it.status == VisionAspectStatus.UNKNOWN } -> IqJudgeVerdict.REVIEW
        else -> fallback
    }

    private fun renderFailure(reason: String) = IqJudgeResult(
        verdict = IqJudgeVerdict.REVIEW,
        label = "无法生成截图",
        reason = reason,
        details = mapOf("渲染器" to renderer.label),
    )

    private fun label(verdict: IqJudgeVerdict): String = when (verdict) {
        IqJudgeVerdict.PASSED -> "视觉评分通过"
        IqJudgeVerdict.INCORRECT -> "疑似降智"
        IqJudgeVerdict.REVIEW -> "需人工确认"
        IqJudgeVerdict.FAILED -> "无法判定"
    }

    private companion object {
        const val VISION_FRAME_COUNT = 6
        const val MAX_TOTAL_IMAGE_BYTES = 8_000_000
        const val ANIMATION_ASPECT = "动画连贯性"
    }
}
