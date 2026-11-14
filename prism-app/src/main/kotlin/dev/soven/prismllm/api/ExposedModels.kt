package dev.soven.prismllm.api

import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.routing.domain.candidate.RoutingAccessScope

/**
 * 按协议列出数据面对外暴露的模型 ID。
 *
 * @param scope 本次调用方获准的渠道、静态密钥与对外模型范围。
 *
 * @author 文杰
 * @since 2026-10-23
 */
suspend fun listExposedModelIds(
    app: GatewayApplication,
    protocol: GatewayProtocol? = null,
    scope: RoutingAccessScope = RoutingAccessScope(),
): List<String> =
    app.modelCatalog.listDirectModels(scope)
        .filter { protocol == null || protocol in it.channel.protocols }
        .map { it.publicId }
        .distinct()
        .sorted()
