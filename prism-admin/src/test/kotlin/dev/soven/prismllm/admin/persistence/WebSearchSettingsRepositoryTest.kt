package dev.soven.prismllm.admin.persistence

import dev.soven.prismllm.admin.domain.WebSearchSettingsRecord
import dev.soven.prismllm.shared.crypto.CredentialCipher
import dev.soven.prismllm.testsupport.db.SharedPostgres
import dev.soven.prismllm.testsupport.db.TestDatabase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("component")
class WebSearchSettingsRepositoryTest {
    private lateinit var factory: TestDatabase
    private lateinit var repository: WebSearchSettingsRepository

    @BeforeAll
    fun start() {
        val postgres = SharedPostgres.container
        val databaseUrl = SharedPostgres.newDatabase()
        factory = TestDatabase(databaseUrl, postgres.username, postgres.password)
        repository = WebSearchSettingsRepository(
            factory.database,
            CredentialCipher(ByteArray(32) { 7 }),
            nowMs = { 1_800_000_000_000 },
        )
    }

    @BeforeEach
    fun reset() {
        runBlocking {
            suspendTransaction(factory.database) { WebSearchSettings.deleteAll() }
        }
    }

    @AfterAll
    fun stop() {
        if (::factory.isInitialized) factory.close()
    }

    @Test
    fun `settings are absent until saved and the api key is encrypted at rest`() = runBlocking {
        assertNull(repository.load())

        repository.save(
            WebSearchSettingsRecord(
                enabled = true,
                provider = "tavily",
                apiKey = "tvly-secret",
                baseUrl = null,
                maxResults = 8,
                timeoutMs = 10_000,
                maxRounds = 4,
                maxPageChars = 1_200,
                updatedAt = 0,
            ),
        )

        val loaded = assertNotNull(repository.load())
        assertEquals(true, loaded.enabled)
        assertEquals("tavily", loaded.provider)
        assertEquals("tvly-secret", loaded.apiKey)
        assertEquals(8, loaded.maxResults)
        assertEquals(10_000, loaded.timeoutMs)
        assertEquals(4, loaded.maxRounds)
        assertEquals(1_200, loaded.maxPageChars)
        assertEquals(1_800_000_000_000, loaded.updatedAt)

        val ciphertext = suspendTransaction(factory.database) {
            WebSearchSettings.selectAll().single()[WebSearchSettings.apiKeyCiphertext]
        }
        assertFalse(ciphertext.contains("tvly-secret"))
    }

    @Test
    fun `an unreadable ciphertext does not break loading`() = runBlocking {
        repository.save(
            WebSearchSettingsRecord(
                enabled = true,
                provider = "tavily",
                apiKey = "tvly-secret",
                baseUrl = null,
                maxResults = 6,
                timeoutMs = 15_000,
                maxRounds = 6,
                maxPageChars = 1_500,
                updatedAt = 0,
            ),
        )

        val rotated = WebSearchSettingsRepository(factory.database, CredentialCipher(ByteArray(32) { 9 }))
        val loaded = assertNotNull(rotated.load())

        assertNull(loaded.apiKey)
        assertTrue(loaded.apiKeyUnreadable)
        assertEquals("tavily", loaded.provider)
    }

    @Test
    fun `saving other settings can keep an unreadable ciphertext`() = runBlocking {
        repository.save(
            WebSearchSettingsRecord(
                enabled = true,
                provider = "tavily",
                apiKey = "tvly-secret",
                baseUrl = null,
                maxResults = 6,
                timeoutMs = 15_000,
                maxRounds = 6,
                maxPageChars = 1_500,
                updatedAt = 0,
            ),
        )

        val rotated = WebSearchSettingsRepository(factory.database, CredentialCipher(ByteArray(32) { 9 }))
        rotated.save(
            WebSearchSettingsRecord(
                enabled = false,
                provider = "tavily",
                apiKey = null,
                baseUrl = null,
                maxResults = 6,
                timeoutMs = 15_000,
                maxRounds = 6,
                maxPageChars = 1_500,
                updatedAt = 0,
            ),
            preserveApiKey = true,
        )

        val kept = assertNotNull(rotated.load())
        assertFalse(kept.enabled)
        assertTrue(kept.apiKeyUnreadable)

        rotated.save(
            WebSearchSettingsRecord(
                enabled = false,
                provider = "tavily",
                apiKey = null,
                baseUrl = null,
                maxResults = 6,
                timeoutMs = 15_000,
                maxRounds = 6,
                maxPageChars = 1_500,
                updatedAt = 0,
            ),
        )

        assertFalse(assertNotNull(rotated.load()).apiKeyUnreadable)
    }
}
