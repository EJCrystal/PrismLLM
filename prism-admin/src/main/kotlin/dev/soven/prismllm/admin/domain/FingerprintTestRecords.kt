package dev.soven.prismllm.admin.domain

import dev.soven.prismllm.shared.domain.Usage

/** 一次模型指纹检测的采样摘要与本地排名结果。 */
data class FingerprintTestRunRecord(
    val id: Long,
    val channelId: String,
    val channelName: String,
    val channelKeyId: String?,
    val channelKeyName: String?,
    val model: String,
    val protocol: String,
    val success: Boolean,
    val statusCode: Int?,
    val latencyMs: Long,
    val decision: String,
    val prediction: String?,
    val predictionName: String?,
    val probability: Double?,
    val usedOutputs: Int,
    val totalOutputs: Int,
    val analysisJson: String,
    val samplesJson: String,
    val errorMessage: String?,
    val usage: Usage?,
    val createdAt: Long,
)

/** 尚未分配数据库 id 的指纹检测结果。 */
data class NewFingerprintTestRun(
    val channelId: String,
    val channelName: String,
    val channelKeyId: String?,
    val channelKeyName: String?,
    val model: String,
    val protocol: String,
    val success: Boolean,
    val statusCode: Int?,
    val latencyMs: Long,
    val decision: String,
    val prediction: String?,
    val predictionName: String?,
    val probability: Double?,
    val usedOutputs: Int,
    val totalOutputs: Int,
    val analysisJson: String,
    val samplesJson: String,
    val errorMessage: String?,
    val usage: Usage?,
    val createdAt: Long = System.currentTimeMillis(),
)