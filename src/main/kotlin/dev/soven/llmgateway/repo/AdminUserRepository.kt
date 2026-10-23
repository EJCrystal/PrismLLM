package dev.soven.llmgateway.repo

import dev.soven.llmgateway.db.AdminUsers
import dev.soven.llmgateway.domain.AdminUserRecord
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.util.UUID

/**
 * 管理员账号持久化。
 *
 * @author 文杰
 * @since 2026-10-21
 */
class AdminUserRepository(private val database: Database) {
    suspend fun count(): Long = suspendTransaction(database) {
        AdminUsers.selectAll().count()
    }

    suspend fun findByUsername(username: String): AdminUserRecord? = suspendTransaction(database) {
        AdminUsers.selectAll().where { AdminUsers.username eq username }.singleOrNull()?.let(::toRecord)
    }

    suspend fun create(username: String, passwordHash: String): AdminUserRecord = suspendTransaction(database) {
        val id = UUID.randomUUID().toString()
        AdminUsers.insert {
            it[AdminUsers.id] = id
            it[AdminUsers.username] = username
            it[AdminUsers.passwordHash] = passwordHash
            it[createdAt] = System.currentTimeMillis()
        }
        AdminUserRecord(id, username, passwordHash)
    }

    private fun toRecord(row: ResultRow) = AdminUserRecord(
        id = row[AdminUsers.id],
        username = row[AdminUsers.username],
        passwordHash = row[AdminUsers.passwordHash],
    )
}
