package dev.soven.prismllm.admin.persistence

import dev.soven.prismllm.testsupport.db.SharedPostgres
import dev.soven.prismllm.testsupport.db.TestDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import kotlin.test.assertNotNull
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
    fun `saved hosts are returned in order and stale revisions are rejected`() = runBlocking {
        assertNull(repository.save(listOf("proxy.example"), expectedRevision = 5))

        val first = assertNotNull(repository.save(listOf("*.onesproxy.vip", "proxy.example"), expectedRevision = 0))
        assertEquals(listOf("*.onesproxy.vip", "proxy.example"), first.allowedHosts)
        assertEquals(1, first.revision)
        assertEquals(1_800_000_000_000, first.updatedAt)

        val second = assertNotNull(repository.save(listOf("proxy.example"), expectedRevision = 1))
        assertEquals(listOf("proxy.example"), second.allowedHosts)
        assertEquals(2, second.revision)
        assertEquals(second, repository.load())

        // 两个实例读到同一版本时，后写入者必须收到冲突而不是静默覆盖。
        assertNull(repository.save(listOf("other.example"), expectedRevision = 1))
        assertEquals(second, repository.load())
    }

    @Test
    fun `concurrent writers with the same expected revision only one wins`() = runBlocking {
        assertNotNull(repository.save(listOf("base.example"), expectedRevision = 0))

        val results = listOf("writer-a.example", "writer-b.example").map { host ->
            async(Dispatchers.IO) { repository.save(listOf(host), expectedRevision = 1) }
        }.awaitAll()

        assertEquals(1, results.count { it != null })
        assertEquals(2, repository.load()?.revision)
    }

    @Test
    fun `blind save still advances the revision`() = runBlocking {
        assertNotNull(repository.save(listOf("proxy.example"), expectedRevision = 0))
        val updated = assertNotNull(repository.save(listOf("other.example"), expectedRevision = null))
        assertEquals(2, updated.revision)
        assertEquals(listOf("other.example"), repository.load()?.allowedHosts)
    }

    @Test
    fun `corrupt stored hosts fall back to an empty list instead of failing startup`() = runBlocking {
        assertNotNull(repository.save(listOf("proxy.example"), expectedRevision = 0))
        suspendTransaction(factory.database) {
            ChannelEgressSettings.update({ ChannelEgressSettings.id eq 1 }) {
                it[allowedHosts] = "{not-json"
            }
        }

        assertEquals(emptyList(), repository.load()?.allowedHosts)
    }
}
