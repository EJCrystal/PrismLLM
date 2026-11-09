package dev.soven.llmgateway.domain

import dev.soven.llmgateway.shared.domain.UpstreamRequestOverrides

data class UpstreamRequestSettingsRecord(
    val overrides: UpstreamRequestOverrides = UpstreamRequestOverrides(),
    val updatedAt: Long = 0,
)
