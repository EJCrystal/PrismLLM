package dev.soven.prismllm.antigravity

import dev.soven.prismllm.admin.domain.AntigravityAgySettingsRecord
import dev.soven.prismllm.admin.persistence.AntigravityAgySettingsRepository
import dev.soven.prismllm.config.AntigravityAgyConfig
import dev.soven.prismllm.shared.error.GatewayError
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Tag("unit")
class AntigravityAgyRuntimeTest {
    private fun repository(initial: AntigravityAgySettingsRecord? = null) = mockk<AntigravityAgySettingsRepository> {
        coEvery { load() } returns initial
        coEvery { save(any()) } answers { firstArg<AntigravityAgySettingsRecord>().copy(updatedAt = 1) }
    }

    private suspend fun waitFor(runtime: AntigravityAgyRuntime, status: String) = withTimeout(3000) {
        while (runtime.snapshot().status != status) delay(5)
    }

    @Test
    fun defaultOffNeverChecksOrInstallsDependencies() = runBlocking {
        AntigravityAgyRuntime(AntigravityAgyConfig.defaults(), repository(), {}, prepare = { _, _, _ -> error("Must stay opt-in") }).use { runtime ->
            runtime.start()
            assertFalse(runtime.snapshot().enabled)
            assertEquals("DISABLED", runtime.snapshot().status)
            assertNull(runtime.login)
        }
    }

    @Test
    fun savedDisableAndProxyRemainAuthoritativeAfterRestart() = runBlocking {
        val saved = AntigravityAgySettingsRecord(false, "http://127.0.0.1:7898")
        AntigravityAgyRuntime(AntigravityAgyConfig.defaults(), repository(saved), {},
            prepare = { _, _, _ -> error("Saved disable must stay opt-in") }).use { runtime ->
            runtime.start()
            assertFalse(runtime.snapshot().enabled)
            assertEquals(saved.proxy, runtime.snapshot().proxy)
            assertEquals("DISABLED", runtime.snapshot().status)
            assertNull(runtime.login)
            assertNull(runtime.gateway)
        }
    }

    @Test
    fun failedSettingsSaveNeverStartsDependencyInstallation() = runBlocking {
        val repository = repository()
        coEvery { repository.save(any()) } throws IllegalStateException("fixture database failure")
        AntigravityAgyRuntime(AntigravityAgyConfig.defaults(), repository, {}, prepare = { _, _, _ -> error("No committed enable") }).use { runtime ->
            runtime.start()
            assertFailsWith<IllegalStateException> { runtime.update(true, "http://127.0.0.1:7897") }
            assertFalse(runtime.snapshot().enabled)
            assertNull(runtime.login)
        }
    }

    @Test
    fun enableReturnsWhilePreparationRunsAndNeverExposesLoginUntilReady() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        AntigravityAgyRuntime(AntigravityAgyConfig.defaults(), repository(), {}, prepare = { _, report, _ ->
            report("INSTALLING", listOf("Bubblewrap"))
            started.complete(Unit)
            finish.await()
        }).use { runtime ->
            runtime.start()
            runtime.update(true, "http://127.0.0.1:7897")
            started.await()
            assertEquals("INSTALLING", runtime.snapshot().status)
            assertEquals(listOf("Bubblewrap"), runtime.snapshot().missingDependencies)
            assertNull(runtime.login)
            assertFailsWith<GatewayError> { runtime.update(true, "http://127.0.0.1:7898") }
            finish.complete(Unit)
            waitFor(runtime, "READY")
            assertNotNull(runtime.login)
            runtime.update(false, "http://127.0.0.1:7897")
            assertNull(runtime.login)
            assertEquals("DISABLED", runtime.snapshot().status)
        }
    }

    @Test
    fun disableLetsCurrentPackageStepFinishButDoesNotActivateServices() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        AntigravityAgyRuntime(AntigravityAgyConfig.defaults(), repository(), {}, prepare = { _, report, _ ->
            report("INSTALLING", listOf("Bubblewrap"))
            started.complete(Unit)
            finish.await()
        }).use { runtime ->
            runtime.start()
            runtime.update(true, "http://127.0.0.1:7897")
            started.await()
            assertEquals("STOPPING", runtime.update(false, "http://127.0.0.1:7897").status)
            finish.complete(Unit)
            waitFor(runtime, "DISABLED")
            assertNull(runtime.login)
        }
    }

    @Test
    fun savedEnableRestoresAfterRestartAndFailuresCanBeRetried() = runBlocking {
        var attempts = 0
        AntigravityAgyRuntime(AntigravityAgyConfig.defaults(), repository(AntigravityAgySettingsRecord(true, "http://127.0.0.1:7898")), {},
            prepare = { config, _, _ ->
                assertEquals("http://127.0.0.1:7898", config.proxy)
                if (++attempts == 1) throw AgyPreparationException("fixture missing permission")
            }).use { runtime ->
            runtime.start()
            waitFor(runtime, "FAILED")
            assertNull(runtime.login)
            assertEquals("fixture missing permission", runtime.snapshot().error)
            runtime.update(true, "http://127.0.0.1:7898")
            waitFor(runtime, "READY")
            assertEquals(2, attempts)
        }
    }
}
