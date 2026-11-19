package dev.soven.prismllm.db

import dev.soven.prismllm.config.GatewayConfig
import dev.soven.prismllm.testsupport.db.SharedPostgres
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.junit.jupiter.api.Tag
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
