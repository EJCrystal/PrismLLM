package dev.soven.prismllm.admin.persistence

import dev.soven.prismllm.testsupport.db.SharedPostgres
import dev.soven.prismllm.testsupport.db.TestDatabase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("component")
class ChannelEgressSettingsRepositoryTest {
    private lateinit var factory: TestDatabase
    private lateinit var repository: ChannelEgressSettingsRepository

    @BeforeAll
    fun start() {
        val postgres = SharedPostgres.container
        val databaseUrl = SharedPostgres.newDatabase()
        factory = TestDatabase(databaseUrl, postgres.username, postgres.password)
        repository = ChannelEgressSettingsRepository(factory.database, nowMs = { 1_800_000_000_000 })
    }

    @BeforeEach
    fun reset() {
        runBlocking {
            suspendTransaction(factory.database) { ChannelEgressSettings.deleteAll() }
        }
    }

    @AfterAll
    fun stop() {
        if (::factory.isInitialized) factory.close()
    }

    @Test
    fun `absent settings fall back to deployment defaults`() = runBlocking {
        assertNull(repository.load())
    }

    @Test
    fun `saved hosts are returned in order and replace previous entries`() = runBlocking {
        val first = repository.save(listOf("*.onesproxy.vip", "proxy.example"), revision = 1)
        assertEquals(listOf("*.onesproxy.vip", "proxy.example"), first.allowedHosts)
        assertEquals(1, first.revision)
        assertEquals(1_800_000_000_000, first.updatedAt)

        val second = repository.save(listOf("proxy.example"), revision = 2)
        assertEquals(listOf("proxy.example"), second.allowedHosts)
        assertEquals(2, second.revision)
        assertEquals(second, repository.load())
    }

    @Test
    fun `corrupt stored hosts fall back to an empty list instead of failing startup`() = runBlocking {
        repository.save(listOf("proxy.example"), revision = 1)
        suspendTransaction(factory.database) {
            ChannelEgressSettings.update({ ChannelEgressSettings.id eq 1 }) {
                it[allowedHosts] = "{not-json"
            }
        }

        assertEquals(emptyList(), repository.load()?.allowedHosts)
    }
}
