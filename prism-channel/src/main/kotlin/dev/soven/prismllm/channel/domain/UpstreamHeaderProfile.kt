package dev.soven.prismllm.channel.domain

import kotlinx.serialization.Serializable

/** A complete, validated upstream client-header snapshot selected by profile ID. */
@Serializable
data class UpstreamHeaderProfile(
    val id: String,
    val name: String,
    val version: String,
    val headers: Map<String, String>,
    val source: String = "builtin",
    val fetchedAt: Long = 0,
)

/** Runtime view of the header-profile catalog exposed by the admin API. */
data class UpstreamHeaderCatalogSnapshot(
    val revision: String,
    val checkedAt: Long,
    val successAt: Long?,
    val lastError: String?,
    val refreshing: Boolean,
    val autoRefreshEnabled: Boolean,
    val refreshIntervalMs: Long,
    val profiles: List<UpstreamHeaderProfile>,
    val history: Map<String, List<UpstreamHeaderProfile>>,
    val pins: Map<String, String>,
)
