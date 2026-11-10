package dev.soven.prismllm.shared.domain

/**
 * 数据面可路由的上游凭据。静态渠道密钥与订阅账号都实现这一接口，
 * 路由、冷却、并发占用和凭据解析不再依赖 channel_keys 这一张表。
 *
 * 接口位于共享内核：channel 与 subscription-oauth 各自实现，routing 只依赖角色接口；
 * 不能声明为 sealed，实现跨模块后需要保持可扩展，具体分支由使用方的 `when` 显式兜底。
 *
 * @author 文杰
 * @since 2026-10-21
 */
interface UpstreamCredential {
    val id: String
    val name: String
    val priority: Int
    val models: List<String>
    val enabled: Boolean

    val isOAuth: Boolean

    fun supports(upstreamModel: String): Boolean

    fun isRoutable(now: Long = System.currentTimeMillis()): Boolean
}