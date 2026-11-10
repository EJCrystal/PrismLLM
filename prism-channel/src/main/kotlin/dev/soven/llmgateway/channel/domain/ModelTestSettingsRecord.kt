package dev.soven.llmgateway.channel.domain

/** 渠道模型连通性测试的全局默认值。 */
data class ModelTestSettingsRecord(
    val defaultPrompt: String,
    val updatedAt: Long,
)
