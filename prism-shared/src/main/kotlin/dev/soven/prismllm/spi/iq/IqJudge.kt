package dev.soven.prismllm.spi.iq

/**
 * 智商检测判分插件的稳定入口。
 *
 * 插件只接收模型生成的 HTML、题目、模型名和本地结构检查结果，不接收任何渠道地址或密钥。
 * 实现必须提供公开的无参构造函数，并通过
 * META-INF/services/dev.soven.prismllm.spi.iq.IqJudge 注册。
 *
 * 该接口使用阻塞方法，方便 Java、Kotlin 和其他 JVM 语言实现；调用方会在专用的有界线程池中
 * 执行插件并施加超时。插件不得启动长生命周期线程，并应响应线程中断。
 */
interface IqJudge : AutoCloseable {
    /** 稳定的机器可读 ID，只允许小写字母、数字、.、_ 和 -。 */
    val id: String

    /** 管理界面展示名称。 */
    val displayName: String
        get() = id

    /** 管理界面说明；插件可覆盖。 */
    val description: String
        get() = ""

    /** SPI 主版本。当前唯一支持的版本是 1。 */
    val apiVersion: Int
        get() = API_VERSION

    fun judge(request: IqJudgeRequest): IqJudgeResult

    override fun close() {}

    companion object {
        const val API_VERSION = 1
        const val ID_PATTERN = "[a-z0-9][a-z0-9._-]{0,63}"
    }
}

/** 传给判分器的最小上下文；不包含渠道凭据。 */
data class IqJudgeRequest(
    val benchmark: String,
    val html: String,
    val model: String,
    val structure: IqStructureSummary,
)

data class IqStructureSummary(
    val checks: List<IqStructureCheck>,
    val htmlPresent: Boolean,
    val svgPresent: Boolean,
    val animationPresent: Boolean,
    val selfContained: Boolean,
) {
    val allPassed: Boolean
        get() = checks.all { it.passed }

    /** 只是辅助检查、不参与自动判定的项；其余项全部通过才算结构合格。 */
    val allDecisivePassed: Boolean
        get() = checks.all { it.passed || !it.decisive }
}

/**
 * 一项结构检查。[decisive] 为 false 时只作为提示展示，不参与判定：
 * 例如需要视觉判断才能确认的跨骑遮挡顺序，离线解析只能检查显式的命名标记，
 * 没打标记的作品不该因此被判失败。
 */
data class IqStructureCheck(
    val id: String,
    val label: String,
    val passed: Boolean,
    val detail: String? = null,
    val decisive: Boolean = true,
)

enum class IqJudgeVerdict(val value: String) {
    PASSED("passed"),
    INCORRECT("incorrect"),
    REVIEW("review"),
    FAILED("failed"),
}

data class IqJudgeResult(
    val verdict: IqJudgeVerdict,
    val label: String,
    val reason: String? = null,
    /** 0–100；不评分时为 null。 */
    val score: Double? = null,
    /** 插件可返回少量结构化补充信息，核心只做展示。 */
    val details: Map<String, String> = emptyMap(),
)
