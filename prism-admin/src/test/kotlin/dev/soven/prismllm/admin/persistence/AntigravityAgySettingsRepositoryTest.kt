package dev.soven.prismllm.admin.persistence

import dev.soven.prismllm.admin.domain.AntigravityAgySettingsRecord
import dev.soven.prismllm.testsupport.db.SharedPostgres
import dev.soven.prismllm.testsupport.db.TestDatabase
import dev.soven.prismllm.shared.crypto.CredentialCipher
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
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
    private val cipher = CredentialCipher(ByteArray(32) { it.toByte() })

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
        val repository = AntigravityAgySettingsRepository(factory.database, cipher)
        assertNull(repository.load())
        val saved = repository.save(AntigravityAgySettingsRecord(true, "http://proxy.example:7897", "agy-user", "agy-secret"))
        assertTrue(saved.updatedAt > 0)
        val restored = AntigravityAgySettingsRepository(factory.database, cipher).load()
        assertEquals(saved, restored)
        val row = suspendTransaction(factory.database) { AntigravityAgySettings.selectAll().single() }
        assertTrue("agy-secret" !in row[AntigravityAgySettings.proxyPasswordCiphertext])
        assertTrue("agy-user" !in row[AntigravityAgySettings.proxyPasswordCiphertext])
        val disabled = repository.save(saved.copy(enabled = false))
        assertEquals(disabled, repository.load())
    }
}
