package dev.soven.llmgateway.admin.persistence

import org.jetbrains.exposed.v1.core.Table

/**
 * 管理台可编辑的 IQ 视觉判分配置；没有行时沿用环境变量默认值。
 *
 * 只包含可热切换的字段：渲染宽度与浏览器路径属于进程级绑定，仍由部署环境变量决定。
 */
object IqVisionSettings : Table("iq_vision_settings") {
    val id = integer("id")
    val enabled = bool("enabled")
    val baseUrl = varchar("base_url", 500).default("")
    val model = varchar("model", 200).default("")
    val apiKeyCiphertext = text("api_key_ciphertext").default("")
    val apiKeyNonce = text("api_key_nonce").default("")
    val apiKeyKeyVersion = integer("api_key_key_version").default(1)
    val visionTimeoutMs = long("vision_timeout_ms")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}
