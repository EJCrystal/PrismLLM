package dev.soven.prismllm.admin.domain

data class AntigravityAgySettingsRecord(
    val enabled: Boolean,
    val proxy: String,
    val updatedAt: Long = 0,
)
