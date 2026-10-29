package dev.soven.llmgateway.repo

import dev.soven.llmgateway.db.UpstreamHeaderCatalogState
import dev.soven.llmgateway.db.UpstreamHeaderProfilePins
import dev.soven.llmgateway.db.UpstreamHeaderProfileVersions
import dev.soven.llmgateway.domain.UpstreamHeaderProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert

class UpstreamHeaderProfileVersionConflictException :
    IllegalStateException("同一请求头版本不能变更内容")

data class UpstreamHeaderCatalogRecord(
    val profiles: List<UpstreamHeaderProfile>,
    val history: Map<String, List<UpstreamHeaderProfile>>,
    val pins: Map<String, String>,
    val checkedAt: Long = 0,
    val successAt: Long? = null,
    val lastError: String? = null,
)

/** Persists versioned header profiles, pins, and the latest refresh result. */
class UpstreamHeaderCatalogRepository(private val database: Database) {
    suspend fun load(defaults: List<UpstreamHeaderProfile>): UpstreamHeaderCatalogRecord = suspendTransaction(database) {
        val versions = UpstreamHeaderProfileVersions.selectAll()
            .orderBy(UpstreamHeaderProfileVersions.fetchedAt to SortOrder.DESC)
            .map(::toProfile)
        val history = versions.groupBy { it.id }
        val pins = UpstreamHeaderProfilePins.selectAll().associate {
            it[UpstreamHeaderProfilePins.profileId] to it[UpstreamHeaderProfilePins.version]
        }
        val latest = history.mapValues { (_, values) -> values.first() }
        val profiles = defaults.map { default ->
            val pinned = pins[default.id]?.let { version ->
                history[default.id]?.firstOrNull { it.version == version }
            }
            pinned ?: latest[default.id] ?: default
        }
        val state = UpstreamHeaderCatalogState.selectAll()
            .where { UpstreamHeaderCatalogState.id eq SINGLETON_ID }
            .singleOrNull()
        UpstreamHeaderCatalogRecord(
            profiles = profiles,
            history = history,
            pins = pins,
            checkedAt = state?.get(UpstreamHeaderCatalogState.checkedAt) ?: 0,
            successAt = state?.get(UpstreamHeaderCatalogState.successAt),
            lastError = state?.get(UpstreamHeaderCatalogState.lastError),
        )
    }

    suspend fun saveRefresh(
        profiles: List<UpstreamHeaderProfile>,
        checkedAt: Long,
        successAt: Long?,
        lastError: String?,
    ): UpstreamHeaderCatalogRecord = suspendTransaction(database) {
        profiles.forEach { profile ->
            val encodedHeaders = Json.encodeToString(
                MapSerializer(String.serializer(), String.serializer()),
                profile.headers,
            )
            val inserted = UpstreamHeaderProfileVersions.insertIgnore {
                it[profileId] = profile.id
                it[version] = profile.version
                it[name] = profile.name
                it[headers] = encodedHeaders
                it[sourceLabel] = profile.source
                it[fetchedAt] = checkedAt
            }
            if (inserted.insertedCount == 0) {
                val existing = UpstreamHeaderProfileVersions.selectAll().where {
                    (UpstreamHeaderProfileVersions.profileId eq profile.id) and
                        (UpstreamHeaderProfileVersions.version eq profile.version)
                }.single()
                val existingHeaders = decodeHeaders(existing[UpstreamHeaderProfileVersions.headers])
                if (existingHeaders != profile.headers) {
                    throw UpstreamHeaderProfileVersionConflictException()
                }
                UpstreamHeaderProfileVersions.update({
                    (UpstreamHeaderProfileVersions.profileId eq profile.id) and
                        (UpstreamHeaderProfileVersions.version eq profile.version)
                }) {
                    it[fetchedAt] = checkedAt
                }
            }
            val ids = UpstreamHeaderProfileVersions.selectAll()
                .where { UpstreamHeaderProfileVersions.profileId eq profile.id }
                .orderBy(UpstreamHeaderProfileVersions.fetchedAt to SortOrder.DESC)
                .map { it[UpstreamHeaderProfileVersions.version] }
            val pinnedVersion = UpstreamHeaderProfilePins.selectAll()
                .where { UpstreamHeaderProfilePins.profileId eq profile.id }
                .singleOrNull()
                ?.get(UpstreamHeaderProfilePins.version)
            ids.drop(MAX_VERSIONS_PER_PROFILE).forEach { stale ->
                if (stale != profile.version && stale != pinnedVersion) {
                    UpstreamHeaderProfileVersions.deleteWhere {
                        (UpstreamHeaderProfileVersions.profileId eq profile.id) and
                            (UpstreamHeaderProfileVersions.version eq stale)
                    }
                }
            }
        }
        val previousSuccessAt = UpstreamHeaderCatalogState.selectAll()
            .where { UpstreamHeaderCatalogState.id eq SINGLETON_ID }
            .singleOrNull()
            ?.get(UpstreamHeaderCatalogState.successAt)
        UpstreamHeaderCatalogState.upsert(UpstreamHeaderCatalogState.id) {
            it[id] = SINGLETON_ID
            it[UpstreamHeaderCatalogState.checkedAt] = checkedAt
            it[UpstreamHeaderCatalogState.successAt] = successAt ?: previousSuccessAt
            it[UpstreamHeaderCatalogState.lastError] = lastError
        }
        loadInTransaction()
    }

    suspend fun saveFailedRefresh(checkedAt: Long, error: String): UpstreamHeaderCatalogRecord =
        suspendTransaction(database) {
            val previousSuccessAt = UpstreamHeaderCatalogState.selectAll()
                .where { UpstreamHeaderCatalogState.id eq SINGLETON_ID }
                .singleOrNull()
                ?.get(UpstreamHeaderCatalogState.successAt)
            UpstreamHeaderCatalogState.upsert(UpstreamHeaderCatalogState.id) {
                it[id] = SINGLETON_ID
                it[UpstreamHeaderCatalogState.checkedAt] = checkedAt
                it[UpstreamHeaderCatalogState.successAt] = previousSuccessAt
                it[UpstreamHeaderCatalogState.lastError] = error.take(1_000)
            }
            loadInTransaction()
        }

    suspend fun pin(profileId: String, version: String): UpstreamHeaderCatalogRecord = suspendTransaction(database) {
        val exists = UpstreamHeaderProfileVersions.selectAll().where {
            (UpstreamHeaderProfileVersions.profileId eq profileId) and
                (UpstreamHeaderProfileVersions.version eq version)
        }.any()
        require(exists) { "预设版本不存在" }
        UpstreamHeaderProfilePins.upsert(UpstreamHeaderProfilePins.profileId) {
            it[this.profileId] = profileId
            it[this.version] = version
            it[pinnedAt] = System.currentTimeMillis()
        }
        loadInTransaction()
    }

    suspend fun unpin(profileId: String): UpstreamHeaderCatalogRecord = suspendTransaction(database) {
        UpstreamHeaderProfilePins.deleteWhere { UpstreamHeaderProfilePins.profileId eq profileId }
        loadInTransaction()
    }

    private fun loadInTransaction(): UpstreamHeaderCatalogRecord {
        val versions = UpstreamHeaderProfileVersions.selectAll()
            .orderBy(UpstreamHeaderProfileVersions.fetchedAt to SortOrder.DESC)
            .map(::toProfile)
        val history = versions.groupBy { it.id }
        val pins = UpstreamHeaderProfilePins.selectAll().associate {
            it[UpstreamHeaderProfilePins.profileId] to it[UpstreamHeaderProfilePins.version]
        }
        val state = UpstreamHeaderCatalogState.selectAll()
            .where { UpstreamHeaderCatalogState.id eq SINGLETON_ID }
            .single()
        val latest = history.mapValues { it.value.first() }
        return UpstreamHeaderCatalogRecord(
            profiles = DEFAULT_PROFILE_IDS.mapNotNull { id ->
                pins[id]?.let { version -> history[id]?.firstOrNull { it.version == version } }
                    ?: latest[id]
            },
            history = history,
            pins = pins,
            checkedAt = state[UpstreamHeaderCatalogState.checkedAt],
            successAt = state[UpstreamHeaderCatalogState.successAt],
            lastError = state[UpstreamHeaderCatalogState.lastError],
        )
    }

    private fun toProfile(row: org.jetbrains.exposed.v1.core.ResultRow): UpstreamHeaderProfile {
        val headers = decodeHeaders(row[UpstreamHeaderProfileVersions.headers])
        return UpstreamHeaderProfile(
            id = row[UpstreamHeaderProfileVersions.profileId],
            name = row[UpstreamHeaderProfileVersions.name],
            version = row[UpstreamHeaderProfileVersions.version],
            headers = headers,
            source = row[UpstreamHeaderProfileVersions.sourceLabel],
            fetchedAt = row[UpstreamHeaderProfileVersions.fetchedAt],
        )
    }

    private fun decodeHeaders(encoded: String): Map<String, String> = Json.decodeFromString(
        MapSerializer(String.serializer(), String.serializer()),
        encoded,
    )

    private companion object {
        const val SINGLETON_ID = 1
        const val MAX_VERSIONS_PER_PROFILE = 20
        val DEFAULT_PROFILE_IDS = listOf("claude", "codex", "google", "antigravity")
    }
}
