package dev.soven.prismllm.channel.domain

import dev.soven.prismllm.shared.domain.UpstreamRequestOverrides

data class UpstreamRequestSettingsRecord(
    val overrides: UpstreamRequestOverrides = UpstreamRequestOverrides(),
    val updatedAt: Long = 0,
)
