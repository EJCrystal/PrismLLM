package dev.soven.prismllm.api

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/** Serializes the legacy prompt write and the first plugin installation across gateway processes. */
internal class ConnectivitySettingsLock(private val database: Database) {
    suspend fun <T> withLock(block: suspend () -> T): T = suspendTransaction(database) {
        exec("SELECT pg_advisory_xact_lock(1347570253, 1129270862)") { it.next() }
        block()
    }
}
