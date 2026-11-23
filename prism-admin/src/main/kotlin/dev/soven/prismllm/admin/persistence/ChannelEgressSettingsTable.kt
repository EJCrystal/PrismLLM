package dev.soven.prismllm.admin.persistence

import org.jetbrains.exposed.v1.core.Table

/**
 * 管理台渠道出口代理白名单，单行配置。
 *
 * @author 文杰
 * @since 2026-11-23
 */
object ChannelEgressSettings : Table("channel_egress_settings") {
    val id = integer("id")
    val allowedHosts = text("allowed_hosts").default("[]")
    val revision = long("revision").default(0)
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}
