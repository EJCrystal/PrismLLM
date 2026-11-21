package dev.soven.prismllm.api

import dev.soven.prismllm.plugin.host.PluginHostException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.sql.SQLException

/** Serializes the legacy prompt write and the first plugin installation across gateway processes. */
internal class ConnectivitySettingsLock(private val database: Database) {
    suspend fun <T> withLock(block: suspend () -> T): T = suspendTransaction(database) {
        val previousLockTimeoutMs = checkNotNull(exec("SELECT setting::bigint FROM pg_settings WHERE name = 'lock_timeout'") {
            check(it.next())
            it.getLong(1)
        })
        val lockTimeoutMs = if (previousLockTimeoutMs == 0L) 2_000L else minOf(previousLockTimeoutMs, 2_000L)
        exec("SET LOCAL lock_timeout = '${lockTimeoutMs}ms'")
        try {
            exec("SELECT pg_advisory_xact_lock(1347570253, 1129270862)") { it.next() }
        } catch (cause: SQLException) {
            if (cause.sqlState == "55P03") {
                throw PluginHostException("PLUGIN_BUSY", "Timed out waiting for connectivity settings; retry the request", 503)
                    .also { it.initCause(cause) }
            }
            throw cause
        }
        exec("SET LOCAL lock_timeout = '${previousLockTimeoutMs}ms'")
        block()
    }
}
