package dev.soven.prismllm.routing.readmodel

import dev.soven.prismllm.channel.domain.ChannelRecord
import dev.soven.prismllm.shared.domain.GatewayProtocol

/** 渠道、凭据授权与已保存路由来自同一数据库快照；保留停用对象与原始身份顺序。 */
data class RoutingConfigurationSnapshot(
    val channels: List<ChannelRecord>,
    val routes: List<ModelCredentialRouteSnapshot>,
    /** OAuth 账号各自支持的原生协议；缺少账号投影时不允许使用其凭据。 */
    val oauthProtocols: Map<String, List<GatewayProtocol>>,
)

/** 数据面只通过此端口读取完整配置，禁止拼接独立时点的渠道、OAuth 与路由结果。 */
fun interface RoutingConfigurationReader {
    /** modelId 为 null 时读取全部路由；否则只读该模型的路由，渠道配置仍完整返回。 */
    suspend fun read(modelId: String?): RoutingConfigurationSnapshot
}
