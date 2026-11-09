package dev.soven.llmgateway.api

import dev.soven.llmgateway.shared.domain.GatewayProtocol

/**
 * 按协议列出数据面对外暴露的模型 ID。
 *
 * @author 文杰
 * @since 2026-10-23
 */
suspend fun listExposedModelIds(app: GatewayApplication, protocol: GatewayProtocol? = null): List<String> =
    app.modelCatalog.listDirectModels()
        .filter { protocol == null || protocol in it.channel.protocols }
        .map { it.publicId }
        .distinct()
        .sorted()
