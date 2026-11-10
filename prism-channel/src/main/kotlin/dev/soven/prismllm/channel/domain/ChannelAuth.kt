package dev.soven.prismllm.channel.domain

import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.shared.domain.effectiveAuthType

fun ChannelRecord.authTypeFor(protocol: GatewayProtocol?): String =
    effectiveAuthType(authType, protocolAuthTypes, protocol)
