package dev.soven.prismllm.admin.persistence

import dev.soven.prismllm.admin.domain.AntigravityAgySettingsRecord
import dev.soven.prismllm.testsupport.db.SharedPostgres
import dev.soven.prismllm.testsupport.db.TestDatabase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("component")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AntigravityAgySettingsRepositoryTest {
    private lateinit var factory: TestDatabase

    @BeforeAll
    fun start() {
        val postgres = SharedPostgres.container
        factory = TestDatabase(SharedPostgres.newDatabase(), postgres.username, postgres.password)
    }

    @BeforeEach
    fun reset() = runBlocking {
        suspendTransaction(factory.database) { AntigravityAgySettings.deleteAll() }
        Unit
    }

    @AfterAll
    fun stop() {
        if (::factory.isInitialized) factory.close()
    }

    @Test
    fun settingsAreOptInPersistAcrossRepositoryInstancesAndCanBeDisabled() = runBlocking {
        val repository = AntigravityAgySettingsRepository(factory.database)
        assertNull(repository.load())
        val saved = repository.save(AntigravityAgySettingsRecord(true, "http://127.0.0.1:7897"))
        assertTrue(saved.updatedAt > 0)
        assertEquals(saved, AntigravityAgySettingsRepository(factory.database).load())
        val disabled = repository.save(saved.copy(enabled = false))
        assertEquals(disabled, repository.load())
    }
}
