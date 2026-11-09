package dev.soven.llmgateway.domain

import dev.soven.llmgateway.shared.domain.GatewayProtocol
import dev.soven.llmgateway.shared.domain.effectiveAuthType

fun ChannelRecord.authTypeFor(protocol: GatewayProtocol?): String =
    effectiveAuthType(authType, protocolAuthTypes, protocol)
