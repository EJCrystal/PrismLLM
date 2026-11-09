package dev.soven.llmgateway.shared.domain

/**
 * 一次请求的 token 用量。拿不到的字段保持 null，不估算。
 *
 * @author 文杰
 * @since 2026-10-21
 */
data class Usage(
    val input: Long?,
    val output: Long?,
    val cached: Long?,
    val cacheWrite: Long? = null,
    val reasoning: Long?,
    val total: Long?,
) {
    val known: Boolean
        get() = input != null || output != null || cached != null || cacheWrite != null || reasoning != null || total != null
}
