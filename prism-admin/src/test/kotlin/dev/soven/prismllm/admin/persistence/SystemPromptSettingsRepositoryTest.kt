package dev.soven.prismllm.admin.persistence

import dev.soven.prismllm.admin.domain.SystemPromptRule
import dev.soven.prismllm.admin.domain.SystemPromptSettingsRecord
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
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("component")
class SystemPromptSettingsRepositoryTest {
    private lateinit var factory: TestDatabase
    private lateinit var repository: SystemPromptSettingsRepository

    @BeforeAll
    fun start() {
        val postgres = SharedPostgres.container
        factory = TestDatabase(SharedPostgres.newDatabase(), postgres.username, postgres.password)
    }

    @BeforeEach
    fun reset() = runBlocking {
        suspendTransaction(factory.database) { SystemPromptSettings.deleteAll() }
        repository = SystemPromptSettingsRepository(factory.database, nowMs = { 1_800_000_000_000 })
    }

    @AfterAll
    fun stop() {
        if (::factory.isInitialized) factory.close()
    }

    @Test
    fun defaultsAreDisabledAndSavedSettingsSurviveRestartWithoutMutatingOldSnapshots() = runBlocking {
        val initial = repository.load()
        assertEquals(SystemPromptSettingsRecord(), initial)
        val settings = SystemPromptSettingsRecord(true, listOf(SystemPromptRule("one", "编码助手", prompt = "  hello\nworld  ")))
        val saved = repository.save(settings)
        assertEquals(settings.copy(updatedAt = 1_800_000_000_000), saved)
        assertSame(saved, repository.load())
        assertEquals(saved, SystemPromptSettingsRepository(factory.database).load())
        assertEquals(SystemPromptSettingsRecord(), initial)
    }

    @Test
    fun failedCommitDoesNotPublishOrPersistTheNewSettings() = runBlocking {
        val previous = repository.save(SystemPromptSettingsRecord())
        suspendTransaction(factory.database) {
            exec("ALTER TABLE system_prompt_settings ADD CONSTRAINT reject_test_prompt CHECK (settings NOT LIKE '%fail-save%')")
        }
        try {
            assertFailsWith<Exception> {
                repository.save(SystemPromptSettingsRecord(true, listOf(SystemPromptRule("one", "test", prompt = "fail-save"))))
            }
            assertSame(previous, repository.load())
            assertEquals(previous, SystemPromptSettingsRepository(factory.database).load())
        } finally {
            suspendTransaction(factory.database) { exec("ALTER TABLE system_prompt_settings DROP CONSTRAINT reject_test_prompt") }
        }
    }
}
