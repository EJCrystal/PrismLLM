package dev.soven.prismllm.antigravity

import dev.soven.prismllm.admin.domain.AntigravityAgySettingsRecord
import dev.soven.prismllm.admin.persistence.AntigravityAgySettingsRepository
import dev.soven.prismllm.config.AntigravityAgyConfig
import dev.soven.prismllm.shared.error.GatewayError
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
    fun directEgressIsAllowedAndAuthenticatedProxySecretsNeverAppearInSnapshotsOrArguments() = runBlocking {
        var preparedConfig: AntigravityAgyConfig? = null
        val runtime = AntigravityAgyRuntime(AntigravityAgyConfig.defaults(), repository(), {}, prepare = { config, _, _ ->
            preparedConfig = config
        })
        runtime.use {
            runtime.start()
            runtime.update(true, "")
            waitFor(runtime, "READY")
            assertEquals("", preparedConfig?.proxy)

            runtime.update(true, "http://proxy.example:8080", "agy-user", "agy-secret")
            waitFor(runtime, "READY")
            val authenticated = preparedConfig!!
            assertEquals("agy-user", authenticated.proxyUsername)
            assertEquals("agy-secret", authenticated.proxyPassword)
            assertEquals("agy-user", runtime.snapshot().username)
            assertEquals(true, runtime.snapshot().hasAuthentication)
            assertFalse(Json.encodeToString(runtime.snapshot()).contains("agy-secret"))
            assertFalse(authenticated.loginCommand(authenticated.stateDir).contains("agy-secret"))

            runtime.update(true, "http://proxy.example:8080", "agy-user")
            assertFailsWith<GatewayError> { runtime.update(true, "http://other.example:8080", "agy-user") }
        }
        Unit
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
    fun reenableWaitsForStoppedPreparationAndRunsAllChecksAgain() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val stopObserved = CompletableDeferred<Unit>()
        val firstFinish = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val secondFinish = CompletableDeferred<Unit>()
        var attempts = 0
        AntigravityAgyRuntime(AntigravityAgyConfig.defaults(), repository(), {}, prepare = { _, report, enabled ->
            report("INSTALLING", listOf("Bubblewrap"))
            if (++attempts == 1) {
                firstStarted.complete(Unit)
                while (enabled()) delay(5)
                stopObserved.complete(Unit)
                firstFinish.await()
            } else {
                secondStarted.complete(Unit)
                secondFinish.await()
            }
        }).use { runtime ->
            runtime.start()
            runtime.update(true, "http://127.0.0.1:7897")
            firstStarted.await()
            runtime.update(false, "http://127.0.0.1:7897")
            stopObserved.await()
            assertFailsWith<GatewayError> { runtime.update(true, "http://127.0.0.1:7897") }
            assertEquals("STOPPING", runtime.snapshot().status)
            assertNull(runtime.login)
            assertNull(runtime.gateway)
            firstFinish.complete(Unit)
            waitFor(runtime, "DISABLED")
            runtime.update(true, "http://127.0.0.1:7897")
            secondStarted.await()
            assertEquals(2, attempts)
            assertEquals("INSTALLING", runtime.snapshot().status)
            assertNull(runtime.login)
            assertNull(runtime.gateway)
            secondFinish.complete(Unit)
            waitFor(runtime, "READY")
            assertNotNull(runtime.login)
            assertEquals("READY", runtime.snapshot().status)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun preparationCannotPublishCompletionDuringDisableSettingsSave(fails: Boolean) = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        val saving = CompletableDeferred<Unit>()
        val saved = CompletableDeferred<Unit>()
        val repository = repository()
        coEvery { repository.save(match { !it.enabled }) } coAnswers {
            saving.complete(Unit)
            saved.await()
            firstArg<AntigravityAgySettingsRecord>()
        }
        AntigravityAgyRuntime(AntigravityAgyConfig.defaults(), repository, {}, prepare = { _, report, _ ->
            report("INSTALLING", listOf("Bubblewrap"))
            started.complete(Unit)
            finish.await()
            returned.complete(Unit)
            if (fails) throw AgyPreparationException("fixture preparation failure")
        }).use { runtime ->
            runtime.start()
            runtime.update(true, "http://127.0.0.1:7897")
            started.await()
            val disable = launch { runtime.update(false, "http://127.0.0.1:7897") }
            saving.await()
            try {
                finish.complete(Unit)
                returned.await()
                delay(50)
                assertEquals("INSTALLING", runtime.snapshot().status)
                assertNull(runtime.login)
                assertNull(runtime.gateway)
            } finally {
                saved.complete(Unit)
                disable.join()
            }
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
