package dev.soven.llmgateway.repo

import dev.soven.llmgateway.db.TeamLimits
import dev.soven.llmgateway.quota.TeamLimitPolicy
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.upsertReturning

/**
 * 团队请求速率与并发策略持久化。
 */
class TeamLimitRepository(private val database: Database) {
    suspend fun list(): List<TeamLimitPolicy> = suspendTransaction(database) {
        TeamLimits.selectAll().orderBy(TeamLimits.team).map(::toPolicy)
    }

    suspend fun find(team: String): TeamLimitPolicy? = suspendTransaction(database) {
        TeamLimits.selectAll()
            .where { TeamLimits.team eq team }
            .singleOrNull()
            ?.let(::toPolicy)
    }

    suspend fun upsert(
        team: String,
        requestsPerMinute: Int?,
        maxConcurrentRequests: Int?,
    ): TeamLimitPolicy = suspendTransaction(database) {
        val now = System.currentTimeMillis()
        TeamLimits.upsertReturning(
            TeamLimits.team,
            onUpdateExclude = listOf(TeamLimits.createdAt),
        ) {
            it[TeamLimits.team] = team
            it[TeamLimits.requestsPerMinute] = requestsPerMinute
            it[TeamLimits.maxConcurrentRequests] = maxConcurrentRequests
            it[createdAt] = now
            it[updatedAt] = now
        }
            .single()
            .let(::toPolicy)
    }

    suspend fun delete(team: String): Boolean = suspendTransaction(database) {
        TeamLimits.deleteWhere { TeamLimits.team eq team } > 0
    }

    private fun toPolicy(row: ResultRow) = TeamLimitPolicy(
        team = row[TeamLimits.team],
        requestsPerMinute = row[TeamLimits.requestsPerMinute],
        maxConcurrentRequests = row[TeamLimits.maxConcurrentRequests],
        createdAt = row[TeamLimits.createdAt],
        updatedAt = row[TeamLimits.updatedAt],
    )
}
