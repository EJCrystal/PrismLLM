package dev.soven.prismllm.config

/**
 * 请求体采样日志开关。
 *
 * 只记录控制生成行为的标量参数（输出上限、采样参数、正文数量），用于排查「调用方传了
 * 多大的 max_output_tokens」这类问题。默认关闭；开启后写入 stdout 结构化日志，不落库，
 * 绝不记录 prompt、系统提示或响应正文。
 *
 * @author 文杰
 * @since 2026-11-12
 */
data class RequestSamplingConfig(
    val enabled: Boolean = false,
    /** 成功请求的采样率（0..1）；失败请求始终记录，便于直接对照错误日志。 */
    val sampleRate: Double = 1.0,
) {
    init {
        require(sampleRate in 0.0..1.0) { "REQUEST_SAMPLING_RATE 必须在 0..1 之间" }
    }

    companion object {
        fun fromEnv(env: Map<String, String>): RequestSamplingConfig {
            fun value(name: String, default: String): String =
                env[name]?.takeIf { it.isNotBlank() } ?: default

            val enabled = value("REQUEST_SAMPLING_ENABLED", "false").toBooleanStrictOrNull()
                ?: error("REQUEST_SAMPLING_ENABLED 必须是 true 或 false")
            val rate = value("REQUEST_SAMPLING_RATE", "1.0").toDoubleOrNull()
                ?.takeIf { it in 0.0..1.0 }
                ?: error("REQUEST_SAMPLING_RATE 必须是 0..1 之间的数字")
            return RequestSamplingConfig(enabled, rate)
        }
    }
}
