package dev.soven.prismllm.db

import dev.soven.prismllm.config.GatewayConfig
import dev.soven.prismllm.testsupport.db.SharedPostgres
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.junit.jupiter.api.Tag
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Tag("component")
class DatabaseFactoryTest {
    @Test
    fun pluginDisableGenerationMigrationPreservesExistingInstallationsAndRejectsNegativeHistory() {
        val postgres = SharedPostgres.container
        val url = SharedPostgres.newDatabase()
        val config = GatewayConfig.fromEnv(mapOf(
            "DATABASE_URL" to url,
            "DATABASE_USER" to postgres.username,
            "DATABASE_PASSWORD" to postgres.password,
            "GATEWAY_MASTER_KEY" to Base64.getEncoder().encodeToString(ByteArray(32) { 7 }),
            "ADMIN_PASSWORD" to "test-password",
        ))
        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("ALTER TABLE plugin_installations DROP COLUMN disable_generation")
                statement.execute("DELETE FROM flyway_schema_history WHERE version = '79'")
                statement.execute("""
                    INSERT INTO plugin_installations
                        (plugin_id, plugin_version, package_sha256, source, installed, desired_enabled,
                         approved_permissions_json, configuration_json, revision, updated_at)
                    VALUES ('migration.probe', '1.0.0', repeat('a', 64), 'bundled', true, true,
                            '["diagnostics.channel.probe"]', '{"defaultPrompt":"keep existing"}', 7, 1234)
                """.trimIndent())
            }
            DatabaseFactory(config).use { factory -> assertEquals(setOf("79"), factory.migrate()) }
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM plugin_installations WHERE plugin_id = 'migration.probe'").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(0L, rows.getLong("disable_generation"))
                    assertEquals(7L, rows.getLong("revision"))
                    assertEquals(1234L, rows.getLong("updated_at"))
                    assertTrue(rows.getBoolean("installed"))
                    assertTrue(rows.getBoolean("desired_enabled"))
                    assertEquals("1.0.0", rows.getString("plugin_version"))
                    assertEquals("[\"diagnostics.channel.probe\"]", rows.getString("approved_permissions_json"))
                    assertEquals("{\"defaultPrompt\":\"keep existing\"}", rows.getString("configuration_json"))
                    assertFalse(rows.next())
                }
                assertFailsWith<SQLException> { statement.execute("UPDATE plugin_installations SET disable_generation = -1") }
            }
        }
    }

    @Test
    fun `close unregisters its database while another factory remains usable`() = runBlocking {
        val postgres = SharedPostgres.container
        val config = GatewayConfig.fromEnv(
            mapOf(
                "DATABASE_URL" to SharedPostgres.newDatabase(),
                "DATABASE_USER" to postgres.username,
                "DATABASE_PASSWORD" to postgres.password,
                "GATEWAY_MASTER_KEY" to Base64.getEncoder().encodeToString(ByteArray(32) { 7 }),
                "ADMIN_PASSWORD" to "test-password",
            ),
        )

        DatabaseFactory(config).use { surviving ->
            val survivingManager = TransactionManager.managerFor(surviving.database)
            val closing = DatabaseFactory(config)
            try {
                TransactionManager.managerFor(closing.database)
                assertTrue(closing.ping())

                closing.close()

                assertFailsWith<IllegalStateException> { TransactionManager.managerFor(closing.database) }
                assertFalse(closing.ping(), "the closed connection pool must not remain usable")
                assertSame(survivingManager, TransactionManager.managerFor(surviving.database))
                assertEquals(1, suspendTransaction(surviving.database) {
                    checkNotNull(exec("SELECT 1") { rows ->
                        check(rows.next())
                        rows.getInt(1)
                    })
                })

                closing.close()
                assertFailsWith<IllegalStateException> { TransactionManager.managerFor(closing.database) }
                assertSame(survivingManager, TransactionManager.managerFor(surviving.database))
            } finally {
                closing.close()
            }
        }
    }
}
