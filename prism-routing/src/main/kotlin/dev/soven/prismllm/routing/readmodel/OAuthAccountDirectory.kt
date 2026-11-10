package dev.soven.prismllm.routing.readmodel

import dev.soven.prismllm.shared.domain.UpstreamCredential

/**
 * 路由读模型需要的订阅账号目录。
 *
 * 账号聚合属于订阅账号上下文，routing 只通过这个端口取得「渠道 → 可路由凭据」的投影，
 * 不直接依赖账号仓储或账号业务字段。
 *
 * @author 文杰
 * @since 2026-11-09
 */
fun interface OAuthAccountDirectory {
    suspend fun listByChannel(): Map<String, List<UpstreamCredential>>
}