package dev.soven.llmgateway.domain

/**
 * 请求失败的阶段。用于在请求日志中直接区分路由、上游和流式阶段的错误。
 *
 * @author 文杰
 * @since 2026-10-22
 */
enum class FailureStage(val value: String) {
    REQUEST("request"),
    ROUTING("routing"),
    UPSTREAM("upstream"),
    STREAMING("streaming"),
    CLIENT("client"),
    ;

    companion object {
        fun fromValue(value: String?): FailureStage? = entries.firstOrNull { it.value == value }
    }
}
