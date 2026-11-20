package dev.soven.prismllm.admin.domain

import kotlinx.serialization.json.JsonObject

/** 持久化管理员的插件安装期望；运行时加载状态由宿主单独维护。 */
data class PluginInstallationRecord(
    val id: String,
    val version: String,
    val sha256: String,
    val source: String,
    val installed: Boolean,
    val desiredEnabled: Boolean,
    val approvedPermissions: List<String>,
    val configuration: JsonObject,
    val updatedAt: Long,
    val revision: Long = 0,
    val disableGeneration: Long = 0,
)
