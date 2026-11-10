package dev.soven.prismllm.channel.persistence

import org.jetbrains.exposed.v1.core.Table

/** Singleton global upstream header and JSON merge-patch settings. */
object UpstreamRequestSettings : Table("upstream_request_settings") {
    val id = integer("id")
    val overrides = text("overrides")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

/** Latest scheduled refresh result for the dynamic upstream header catalog. */
object UpstreamHeaderCatalogState : Table("upstream_header_catalog_state") {
    val id = integer("id")
    val checkedAt = long("checked_at")
    val successAt = long("success_at").nullable()
    val lastError = text("last_error").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Persisted versions available for pinning and rollback. */
object UpstreamHeaderProfileVersions : Table("upstream_header_profile_versions") {
    val profileId = varchar("profile_id", 40)
    val version = varchar("version", 100)
    val name = varchar("name", 100)
    val headers = text("headers")
    val sourceLabel = varchar("source", 500)
    val fetchedAt = long("fetched_at")
    override val primaryKey = PrimaryKey(profileId, version)
}

/** A profile pin selects one persisted version instead of the latest snapshot. */
object UpstreamHeaderProfilePins : Table("upstream_header_profile_pins") {
    val profileId = varchar("profile_id", 40)
    val version = varchar("version", 100)
    val pinnedAt = long("pinned_at")
    override val primaryKey = PrimaryKey(profileId)
}