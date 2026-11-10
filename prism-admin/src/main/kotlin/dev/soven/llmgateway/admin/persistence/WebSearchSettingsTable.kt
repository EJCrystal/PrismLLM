package dev.soven.llmgateway.admin.persistence

import org.jetbrains.exposed.v1.core.Table

/** 管理台可编辑的 Web 搜索设置；没有行时沿用环境变量默认值。 */
object WebSearchSettings : Table("web_search_settings") {
    val id = integer("id")
    val enabled = bool("enabled")
    val provider = varchar("provider", 20)
    val apiKeyCiphertext = text("api_key_ciphertext").default("")
    val apiKeyNonce = text("api_key_nonce").default("")
    val apiKeyKeyVersion = integer("api_key_key_version").default(1)
    val baseUrl = varchar("base_url", 500).nullable()
    val maxResults = integer("max_results")
    val timeoutMs = long("timeout_ms")
    val maxRounds = integer("max_rounds")
    val maxPageChars = integer("max_page_chars")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}
