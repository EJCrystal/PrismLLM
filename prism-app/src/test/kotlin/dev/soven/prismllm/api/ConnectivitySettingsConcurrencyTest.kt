package dev.soven.prismllm.api

import dev.soven.prismllm.admin.persistence.PluginInstallationRepository
import dev.soven.prismllm.channel.persistence.ModelTestSettingsRepository
import dev.soven.prismllm.config.GatewayConfig
import dev.soven.prismllm.db.DatabaseFactory
import dev.soven.prismllm.plugin.host.PluginCatalog
import dev.soven.prismllm.plugin.host.PluginHostException
import dev.soven.prismllm.plugin.host.PluginInstallation
import dev.soven.prismllm.plugin.host.PluginInstallationStore
import dev.soven.prismllm.plugin.host.PluginRuntime
import dev.soven.prismllm.plugin.sdk.PluginField
import dev.soven.prismllm.plugin.sdk.PluginManifest
import dev.soven.prismllm.testsupport.db.SharedPostgres
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Real PostgreSQL transactions and independent pools; no server, credentials, or probes are started. */
@Tag("component")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(60)
internal class ConnectivitySettingsConcurrencyTest {
    private lateinit var root: Path

    private lateinit var catalogDirectory: Path
    private lateinit var packageDigest: String

    @BeforeAll
    fun prepareSyntheticPackage(@TempDir directory: Path) {
        root = directory
        catalogDirectory = Files.createDirectory(root.resolve("catalog"))
        val compilerDirectory = Files.createDirectory(root.resolve("compiler"))
        val classes = Files.createDirectory(compilerDirectory.resolve("classes"))
        val source = compilerDirectory.resolve("SettingsPlugin.java")
        Files.writeString(source, """
            package connectivityfixture;
            import dev.soven.prismllm.plugin.sdk.*;
            public final class SettingsPlugin implements DiagnosticPlugin {
                public String getId() { return "prism.connectivity"; }
                public String getVersion() { return "1.0.0"; }
                public int getApiVersion() { return 1; }
                public ProbePlan plan(DiagnosticInput input, DiagnosticContext context) {
                    throw new AssertionError("Settings concurrency tests must not execute probes");
                }
                public DiagnosticReport report(ProbePlan plan, ProbeOutcome outcome) {
                    throw new AssertionError("Settings concurrency tests must not execute reports");
                }
                public void close() {}
            }
        """.trimIndent())
        val compiler = assertNotNull(ToolProvider.getSystemJavaCompiler(), "The existing plugin fixture convention requires a JDK")
        val diagnostics = ByteArrayOutputStream()
        assertEquals(0, compiler.run(null, diagnostics, diagnostics,
            "--release", "21", "-classpath", System.getProperty("java.class.path"),
            "-d", classes.toString(), source.toString()), diagnostics.toString(Charsets.UTF_8))
        val jar = archive(
            "META-INF/services/dev.soven.prismllm.plugin.sdk.DiagnosticPlugin" to "connectivityfixture.SettingsPlugin\n".toByteArray(),
            "connectivityfixture/SettingsPlugin.class" to Files.readAllBytes(classes.resolve("connectivityfixture/SettingsPlugin.class")),
        )
        val manifest = PluginManifest(
            CONNECTIVITY_PLUGIN_ID, VERSION, "Synthetic connectivity settings fixture",
            configuration = listOf(
                PluginField("defaultPrompt", "Prompt", "textarea", required = true,
                    defaultValue = JsonPrimitive(ModelTestSettingsRepository.DEFAULT_PROMPT), maxLength = 4_000),
                PluginField("retainedLabel", "Retained label", "text", required = true,
                    defaultValue = JsonPrimitive("schema default"), maxLength = 200),
            ),
        )
        val json = Json { encodeDefaults = true }
        val packaged = archive(
            "manifest.json" to json.encodeToString(PluginManifest.serializer(), manifest).toByteArray(),
            "ui.json" to """{"schemaVersion":1,"entries":[]}""".toByteArray(),
            "plugin.jar" to jar,
        )
        packageDigest = MessageDigest.getInstance("SHA-256").digest(packaged)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        Files.write(catalogDirectory.resolve("settings.prismplugin"), packaged)
        Files.writeString(catalogDirectory.resolve("catalog.json"), buildJsonObject {
            put("schemaVersion", 1)
            put("packages", JsonArray(listOf(buildJsonObject {
                put("id", CONNECTIVITY_PLUGIN_ID)
                put("version", VERSION)
                put("file", "settings.prismplugin")
                put("sha256", packageDigest)
            })))
        }.toString())
    }

    @AfterAll
    fun restoreTemporaryDirectoryPermissions() {
        // The real installer publishes immutable directories; allow JUnit to remove its own fixture.
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.filter { Files.isDirectory(it) }.forEach { directory ->
                try {
                    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
                } catch (_: UnsupportedOperationException) {
                    // Non-POSIX filesystems do not need this permission restoration.
                }
            }
        }
    }

    @Test
    fun `legacy PUT waits for first creation commit and then saves fields with CAS`() = runBlocking {
        assertPutWaitsForCreation(legacyRowExists = true)
    }

    @Test
    fun `legacy PUT also waits for first creation when the singleton row is absent`() = runBlocking {
        assertPutWaitsForCreation(legacyRowExists = false)
    }

    private suspend fun assertPutWaitsForCreation(legacyRowExists: Boolean) = withFixture {
        if (legacyRowExists) first.settings.save(OLD_PROMPT)
        val initialPrompt = if (legacyRowExists) OLD_PROMPT else ModelTestSettingsRepository.DEFAULT_PROMPT
        val callbackCalls = AtomicInteger()
        val seedRead = CompletableDeferred<Pair<Int, String>>()
        val savedFields = CompletableDeferred<Pair<PluginInstallation, Long>>()
        second.observedStore.beforeSave = { next, expectedRevision -> savedFields.complete(next to expectedRevision) }

        withPausedWrite("plugin_installations", "INSERT") { gate ->
            coroutineScope {
                val creation = async(Dispatchers.IO) {
                    first.runtime.install(CONNECTIVITY_PLUGIN_ID, VERSION, emptyList()) {
                        callbackCalls.incrementAndGet()
                        val pid = assertSeedTransaction(first.database)
                        val prompt = first.settings.loadInTransaction().defaultPrompt
                        seedRead.complete(pid to prompt)
                        configuration(prompt)
                    }
                }
                var put: Deferred<ModelTestSettingsResponse>? = null
                try {
                    // AFTER INSERT has run, but this transaction cannot commit until the gate opens.
                    val creatorPid = awaitAdvisoryWait(GATE_NAMESPACE, GATE_KEY, gate.pid)
                    assertEquals(creatorPid to initialPrompt, seedRead.awaitBounded())
                    assertConnectivityLockHeld(creatorPid)
                    assertNull(second.adapter.find(CONNECTIVITY_PLUGIN_ID), "The installation must still be uncommitted")
                    assertEquals(initialPrompt, second.settings.load().defaultPrompt)

                    val pendingPut = async(Dispatchers.IO) { second.app.saveConnectivitySettings(PUT_PROMPT) }
                    put = pendingPut
                    val putPid = awaitAdvisoryWait(LOCK_NAMESPACE, LOCK_KEY, creatorPid)
                    assertNotEquals(creatorPid, putPid, "The writer must use an independent physical connection")
                    assertFalse(pendingPut.isCompleted)
                    gate.release()

                    assertTrue(creation.awaitBounded().installed)
                    assertEquals(PUT_PROMPT, pendingPut.awaitBounded().defaultPrompt)
                    val (next, expectedRevision) = savedFields.awaitBounded()
                    assertEquals(0L, expectedRevision)
                    assertEquals(1L, next.revision)
                    assertEquals(configuration(PUT_PROMPT), next.configuration, "The field update must retain unrelated configuration")
                    val installed = assertNotNull(first.adapter.find(CONNECTIVITY_PLUGIN_ID))
                    assertEquals(next, installed)
                    assertEquals(1, callbackCalls.get())
                    assertEquals(initialPrompt, first.settings.load().defaultPrompt, "A committed installation routes PUT to plugin configuration")
                    assertEquals(if (legacyRowExists) 1 else 0, observer.scalarInt("SELECT count(*) FROM model_test_settings"))
                    assertEquals(installed, first.adapter.getOrCreate(CONNECTIVITY_PLUGIN_ID) {
                        error("A replay must not replace an already acknowledged PUT")
                    })
                    assertNoConnectivityLocks()
                } finally {
                    try {
                        gate.release()
                    } finally {
                        stopWorkers(creation, put)
                    }
                }
            }
        }
    }

    @Test
    fun `first creation waits for legacy PUT commit and reads the latest prompt`() = runBlocking {
        assertCreationWaitsForPut(legacyRowExists = true)
    }

    @Test
    fun `first creation waits for legacy PUT inserting the missing singleton row`() = runBlocking {
        assertCreationWaitsForPut(legacyRowExists = false)
    }

    private suspend fun assertCreationWaitsForPut(legacyRowExists: Boolean) = withFixture {
        if (legacyRowExists) first.settings.save(OLD_PROMPT)
        val callbackCalls = AtomicInteger()
        val seedRead = CompletableDeferred<String>()
        withPausedWrite("model_test_settings", "INSERT OR UPDATE") { gate ->
            coroutineScope {
                val put = async(Dispatchers.IO) { first.app.saveConnectivitySettings(PUT_PROMPT) }
                var creation: Deferred<*>? = null
                try {
                    val putPid = awaitAdvisoryWait(GATE_NAMESPACE, GATE_KEY, gate.pid)
                    assertConnectivityLockHeld(putPid)
                    assertEquals(if (legacyRowExists) OLD_PROMPT else ModelTestSettingsRepository.DEFAULT_PROMPT,
                        second.settings.load().defaultPrompt, "The legacy update must still be uncommitted")
                    val pendingCreation = async(Dispatchers.IO) {
                        second.runtime.install(CONNECTIVITY_PLUGIN_ID, VERSION, emptyList()) {
                            callbackCalls.incrementAndGet()
                            assertSeedTransaction(second.database)
                            val prompt = second.settings.loadInTransaction().defaultPrompt
                            seedRead.complete(prompt)
                            configuration(prompt)
                        }
                    }
                    creation = pendingCreation
                    val creatorPid = awaitAdvisoryWait(LOCK_NAMESPACE, LOCK_KEY, putPid)
                    assertNotEquals(putPid, creatorPid)
                    assertEquals(0, callbackCalls.get(), "The lazy read must not run before the creator acquires the lock")
                    assertNull(second.adapter.find(CONNECTIVITY_PLUGIN_ID))
                    gate.release()

                    assertEquals(PUT_PROMPT, put.awaitBounded().defaultPrompt)
                    assertTrue(pendingCreation.awaitBounded().installed)
                    assertEquals(PUT_PROMPT, seedRead.awaitBounded())
                    val installed = assertNotNull(first.adapter.find(CONNECTIVITY_PLUGIN_ID))
                    assertEquals(configuration(PUT_PROMPT), installed.configuration)
                    assertEquals(0L, installed.revision, "The first installation must already contain the acknowledged legacy value")
                    assertEquals(PUT_PROMPT, first.settings.load().defaultPrompt)
                    assertEquals(1, callbackCalls.get())
                    assertNoConnectivityLocks()
                } finally {
                    try {
                        gate.release()
                    } finally {
                        stopWorkers(put, creation)
                    }
                }
            }
        }
    }

    @Test
    fun sharedPostgresDisableGenerationSurvivesMissedStopsAndPreservesImmediateConfiguration() = runBlocking {
        withFixture {
            val installed = first.runtime.install(CONNECTIVITY_PLUGIN_ID, VERSION, emptyList()) { configuration(OLD_PROMPT) }
            first.runtime.enable(CONNECTIVITY_PLUGIN_ID, installed.revision, VERSION)
            val initial = assertNotNull(second.adapter.find(CONNECTIVITY_PLUGIN_ID))
            assertEquals(0L, initial.disableGeneration)
            PluginRuntime(PluginCatalog(catalogDirectory), Files.createTempDirectory(root, "loaded-observer-"), second.adapter).use { observerRuntime ->
                observerRuntime.initialize()
                assertTrue(observerRuntime.state(CONNECTIVITY_PLUGIN_ID).admissionEnabled)
                first.runtime.configureFields(CONNECTIVITY_PLUGIN_ID, configuration(PUT_PROMPT))
                assertEquals(configuration(PUT_PROMPT), observerRuntime.state(CONNECTIVITY_PLUGIN_ID).activeConfiguration)
                assertEquals(0L, assertNotNull(second.adapter.find(CONNECTIVITY_PLUGIN_ID)).disableGeneration)
                first.runtime.disable(CONNECTIVITY_PLUGIN_ID)
                val disabled = assertNotNull(second.adapter.find(CONNECTIVITY_PLUGIN_ID))
                assertEquals(1L, disabled.disableGeneration)
                second.runtime.enable(CONNECTIVITY_PLUGIN_ID, disabled.revision, VERSION)
                val after = observerRuntime.state(CONNECTIVITY_PLUGIN_ID)
                assertFalse(after.admissionEnabled)
                assertTrue(after.requiresRestart)
                assertEquals(1, observer.scalarInt("SELECT disable_generation FROM plugin_installations"))
                PluginRuntime(PluginCatalog(catalogDirectory), Files.createTempDirectory(root, "loaded-restart-"), first.adapter).use { restarted ->
                    restarted.initialize()
                    assertTrue(restarted.state(CONNECTIVITY_PLUGIN_ID).admissionEnabled)
                    first.runtime.uninstall(CONNECTIVITY_PLUGIN_ID)
                    val restored = second.runtime.install(CONNECTIVITY_PLUGIN_ID, VERSION, emptyList())
                    second.runtime.enable(CONNECTIVITY_PLUGIN_ID, restored.revision, VERSION)
                    assertEquals(2L, assertNotNull(first.adapter.find(CONNECTIVITY_PLUGIN_ID)).disableGeneration)
                    assertFalse(restarted.state(CONNECTIVITY_PLUGIN_ID).admissionEnabled)
                    assertTrue(restarted.state(CONNECTIVITY_PLUGIN_ID).requiresRestart)
                }
            }
            assertNoConnectivityLocks()
        }
    }

    @Test
    fun `getOrCreate preserves an independently inserted concurrent winner`() = runBlocking {
        assertConcurrentWinnerSurvives(tombstone = false)
    }

    @Test
    fun `getOrCreate preserves an independently inserted concurrent tombstone`() = runBlocking {
        assertConcurrentWinnerSurvives(tombstone = true)
    }

    private suspend fun assertConcurrentWinnerSurvives(tombstone: Boolean) = withFixture {
        val callbackEntered = CompletableDeferred<Unit>()
        val finishCallback = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val winner = installation("concurrent winner", "winner label").copy(
            installed = !tombstone, desiredEnabled = false, revision = 7, updatedAt = 7_000, disableGeneration = 3,
        )
        coroutineScope {
            val creation = async(Dispatchers.IO) {
                first.adapter.getOrCreate(CONNECTIVITY_PLUGIN_ID) {
                    calls.incrementAndGet()
                    assertSeedTransaction(first.database)
                    first.settings.loadInTransaction()
                    callbackEntered.complete(Unit)
                    finishCallback.awaitBounded()
                    installation("losing seed", "losing label")
                }
            }
            try {
                callbackEntered.awaitBounded()
                // This independent store can win the insert without participating in lazy seeding.
                assertTrue(second.adapter.insertIfAbsent(winner))
                finishCallback.complete(Unit)
                assertEquals(winner, creation.awaitBounded())
                assertEquals(winner, second.adapter.find(CONNECTIVITY_PLUGIN_ID))
                assertEquals(winner, second.adapter.getOrCreate(CONNECTIVITY_PLUGIN_ID) {
                    error("An existing winner or tombstone must suppress the lazy callback")
                })
                assertEquals(1, calls.get())
                assertEquals(1, observer.scalarInt("SELECT count(*) FROM plugin_installations"))
                assertNoConnectivityLocks()
            } finally {
                finishCallback.complete(Unit)
                stopWorkers(creation)
            }
        }
    }

    @Test
    fun `a stale field CAS cannot erase an acknowledged legacy PUT from another host`() = runBlocking {
        withFixture {
            first.runtime.install(CONNECTIVITY_PLUGIN_ID, VERSION, emptyList()) {
                assertSeedTransaction(first.database)
                configuration(first.settings.loadInTransaction().defaultPrompt)
            }
            val staleSave = CompletableDeferred<Pair<PluginInstallation, Long>>()
            val finishStaleSave = CompletableDeferred<Unit>()
            val pauseOnce = AtomicBoolean(true)
            first.observedStore.beforeSave = { next, revision ->
                if (pauseOnce.compareAndSet(true, false)) {
                    staleSave.complete(next to revision)
                    finishStaleSave.awaitBounded()
                }
            }
            coroutineScope {
                val otherField = async(Dispatchers.IO) {
                    runCatching {
                        first.runtime.configureFields(CONNECTIVITY_PLUGIN_ID, buildJsonObject { put("retainedLabel", "concurrent label") })
                    }
                }
                try {
                    val (stale, expectedRevision) = staleSave.awaitBounded()
                    assertEquals(0L, expectedRevision)
                    assertEquals(JsonPrimitive(ModelTestSettingsRepository.DEFAULT_PROMPT), stale.configuration["defaultPrompt"])
                    assertNoConnectivityLocks()
                    assertEquals(PUT_PROMPT, second.app.saveConnectivitySettings(PUT_PROMPT).defaultPrompt)
                    assertEquals(1L, assertNotNull(second.adapter.find(CONNECTIVITY_PLUGIN_ID)).revision)
                    finishStaleSave.complete(Unit)

                    val result = otherField.awaitBounded()
                    val failure = result.exceptionOrNull()
                    if (failure != null) {
                        assertTrue(failure is PluginHostException, failure.toString())
                        assertEquals("PLUGIN_REVISION_CONFLICT", failure.code)
                    }
                    val saved = assertNotNull(second.adapter.find(CONNECTIVITY_PLUGIN_ID))
                    assertEquals(JsonPrimitive(PUT_PROMPT), saved.configuration["defaultPrompt"], "A successful PUT must survive the stale CAS")
                    // A host may reject the stale revision or retry its field merge against the winner.
                    assertEquals(JsonPrimitive(if (failure == null) "concurrent label" else RETAINED_LABEL), saved.configuration["retainedLabel"])
                    assertEquals(if (failure == null) 2L else 1L, saved.revision)
                    assertEquals(0, observer.scalarInt("SELECT count(*) FROM model_test_settings"))
                    assertNoConnectivityLocks()
                } finally {
                    finishStaleSave.complete(Unit)
                    stopWorkers(otherField)
                }
            }
        }
    }

    @Test
    fun `rollback after the actual insert leaves no installation and releases the waiting PUT`() = runBlocking {
        withFixture {
            withPausedWrite("plugin_installations", "INSERT", failAfterRelease = true) { gate ->
                coroutineScope {
                    val failedCreation = async(Dispatchers.IO) {
                        runCatching {
                            first.adapter.getOrCreate(CONNECTIVITY_PLUGIN_ID) {
                                assertSeedTransaction(first.database)
                                first.settings.saveInTransaction("uncommitted seed write")
                                installation(first.settings.loadInTransaction().defaultPrompt)
                            }
                        }.exceptionOrNull()
                    }
                    var put: Deferred<ModelTestSettingsResponse>? = null
                    try {
                        val creatorPid = awaitAdvisoryWait(GATE_NAMESPACE, GATE_KEY, gate.pid)
                        assertConnectivityLockHeld(creatorPid)
                        assertNull(second.adapter.find(CONNECTIVITY_PLUGIN_ID))
                        assertEquals(0, observer.scalarInt("SELECT count(*) FROM model_test_settings"), "The callback write must share the uncommitted transaction")
                        val pendingPut = async(Dispatchers.IO) { second.app.saveConnectivitySettings(PUT_PROMPT) }
                        put = pendingPut
                        awaitAdvisoryWait(LOCK_NAMESPACE, LOCK_KEY, creatorPid)
                        gate.release()

                        val failure = assertNotNull(failedCreation.awaitBounded(), "The synthetic AFTER INSERT failure must roll back")
                        assertTrue(generateSequence(failure) { it.cause }.any {
                            it.message?.contains("synthetic connectivity insert rollback") == true
                        }, failure.toString())
                        assertEquals(PUT_PROMPT, pendingPut.awaitBounded().defaultPrompt)
                        assertNull(first.adapter.find(CONNECTIVITY_PLUGIN_ID))
                        assertEquals(PUT_PROMPT, first.settings.load().defaultPrompt)
                        assertEquals(0, observer.scalarInt("SELECT count(*) FROM plugin_installations"))
                        assertNoConnectivityLocks()
                    } finally {
                        try {
                            gate.release()
                        } finally {
                            stopWorkers(failedCreation, put)
                        }
                    }
                }
            }
            // The failed trigger is gone; another pool can immediately acquire the same transaction lock.
            val recovered = second.adapter.getOrCreate(CONNECTIVITY_PLUGIN_ID) {
                assertSeedTransaction(second.database)
                installation(second.settings.loadInTransaction().defaultPrompt)
            }
            assertEquals(configuration(PUT_PROMPT), recovered.configuration)
            assertEquals(0L, recovered.revision)
            assertNoConnectivityLocks()
        }
    }

    @Test
    fun `callback failure rolls back its legacy write and leaves the lock reusable`() = runBlocking {
        withFixture {
            first.settings.save(OLD_PROMPT)
            val failure = assertFailsWith<IllegalStateException> {
                first.adapter.getOrCreate(CONNECTIVITY_PLUGIN_ID) {
                    assertSeedTransaction(first.database)
                    first.settings.saveInTransaction("rolled back callback write")
                    error("synthetic seed callback failure")
                }
            }
            assertEquals("synthetic seed callback failure", failure.message)
            assertNull(second.adapter.find(CONNECTIVITY_PLUGIN_ID))
            assertEquals(OLD_PROMPT, second.settings.load().defaultPrompt)
            assertNoConnectivityLocks()
            second.lock.withLock {
                assertSeedTransaction(second.database)
                assertNull(second.installations.findInTransaction(CONNECTIVITY_PLUGIN_ID))
                second.settings.saveInTransaction(PUT_PROMPT)
            }
            assertEquals(PUT_PROMPT, first.settings.load().defaultPrompt)
            assertNoConnectivityLocks()
        }
    }

    private suspend fun withFixture(block: suspend Fixture.() -> Unit) {
        val postgres = SharedPostgres.container
        val url = SharedPostgres.newDatabase()
        val caseDirectory = Files.createTempDirectory(root, "case-")
        // Bound JDBC itself as well as coroutine synchronization, including a broken-lock regression.
        val separator = if ('?' in url) '&' else '?'
        val config = GatewayConfig.fromEnv(mapOf(
            "DATABASE_URL" to "$url${separator}connectTimeout=5&socketTimeout=25&options=-c%20statement_timeout%3D20000",
            "DATABASE_USER" to postgres.username,
            "DATABASE_PASSWORD" to postgres.password,
            "GATEWAY_MASTER_KEY" to Base64.getEncoder().encodeToString(ByteArray(32) { 7 }),
            "ADMIN_PASSWORD" to "synthetic-settings-test",
        ))
        DatabaseFactory(config).use { firstFactory ->
            DatabaseFactory(config).use { secondFactory ->
                Participant(firstFactory.database, catalogDirectory, caseDirectory.resolve("first")).use { first ->
                    Participant(secondFactory.database, catalogDirectory, caseDirectory.resolve("second")).use { second ->
                        assertNotEquals(first.database, second.database)
                        // Initializing empty hosts exercises real configureFields/CAS without loading code.
                        first.runtime.initialize()
                        second.runtime.initialize()
                        DriverManager.getConnection(config.databaseUrl, config.databaseUser, config.databasePassword).use { observer ->
                            observer.executeSql("DELETE FROM plugin_installations")
                            observer.executeSql("DELETE FROM model_test_settings")
                            Fixture(config, first, second, observer).block()
                        }
                    }
                }
            }
        }
    }

    private inner class Fixture(
        val config: GatewayConfig,
        val first: Participant,
        val second: Participant,
        val observer: Connection,
    ) {
        fun installation(prompt: String, label: String = RETAINED_LABEL) = PluginInstallation(
            CONNECTIVITY_PLUGIN_ID, VERSION, packageDigest, "bundled", installed = true, desiredEnabled = false,
            approvedPermissions = emptyList(), configuration = configuration(prompt, label), updatedAt = 1_000, revision = 0,
        )

        suspend fun awaitAdvisoryWait(namespace: Int, key: Int, blockerPid: Int): Int = withContext(Dispatchers.IO) {
            withTimeout(WAIT_MS) {
                var pid: Int? = null
                while (pid == null) {
                    pid = observer.nullableInt("""
                        SELECT locks.pid FROM pg_locks AS locks
                        WHERE ${lockPredicate(namespace, key)} AND NOT locks.granted
                          AND $blockerPid = ANY(pg_blocking_pids(locks.pid))
                    """.trimIndent())
                    if (pid == null) yield()
                }
                pid
            }
        }

        fun assertConnectivityLockHeld(pid: Int) {
            assertEquals(1, observer.scalarInt("""
                SELECT count(*) FROM pg_locks AS locks
                WHERE ${lockPredicate(LOCK_NAMESPACE, LOCK_KEY)} AND locks.granted AND locks.pid = $pid
            """.trimIndent()), "The seed read and write must be protected by the transaction advisory lock")
        }

        fun assertNoConnectivityLocks() {
            assertEquals(0, observer.scalarInt("""
                SELECT count(*) FROM pg_locks AS locks WHERE ${lockPredicate(LOCK_NAMESPACE, LOCK_KEY)}
            """.trimIndent()), "A completed or rolled back transaction must not leave a held or waiting lock")
        }
    }

    private class Participant(val database: Database, catalog: Path, managed: Path) : AutoCloseable {
        val installations = PluginInstallationRepository(database)
        val settings = ModelTestSettingsRepository(database)
        val lock = ConnectivitySettingsLock(database)
        val adapter = PluginInstallationStoreAdapter(installations, database, lock)
        val observedStore = SaveObserver(adapter)
        val runtime = PluginRuntime(PluginCatalog(catalog), managed, observedStore)
        // Only composition getters are mocked; the extension, lock, repositories, host and CAS are real.
        val app = mockk<GatewayApplication>().also { app ->
            every { app.pluginInstallationRepository } returns installations
            every { app.modelTestSettingsRepository } returns settings
            every { app.connectivitySettingsLock } returns lock
            every { app.pluginRuntime } returns runtime
        }

        override fun close() = runtime.close()
    }

    private class SaveObserver(private val delegate: PluginInstallationStore) : PluginInstallationStore by delegate {
        var beforeSave: suspend (PluginInstallation, Long) -> Unit = { _, _ -> }

        override suspend fun save(installation: PluginInstallation, expectedRevision: Long): Boolean {
            beforeSave(installation, expectedRevision)
            return delegate.save(installation, expectedRevision)
        }
    }

    private class SqlGate(private val connection: Connection, val pid: Int) {
        private var released = false

        fun release() {
            if (!released) {
                connection.rollback()
                released = true
            }
        }
    }

    private suspend fun Fixture.withPausedWrite(
        table: String,
        events: String,
        failAfterRelease: Boolean = false,
        block: suspend (SqlGate) -> Unit,
    ) {
        require(table in setOf("plugin_installations", "model_test_settings"))
        require(events in setOf("INSERT", "INSERT OR UPDATE"))
        DriverManager.getConnection(config.databaseUrl, config.databaseUser, config.databasePassword).use { blocker ->
            blocker.autoCommit = false
            val gate = SqlGate(blocker, blocker.scalarInt("SELECT pg_backend_pid()"))
            try {
                blocker.executeSql("SELECT pg_advisory_xact_lock($GATE_NAMESPACE, $GATE_KEY)")
                observer.executeSql("""
                    CREATE FUNCTION connectivity_concurrency_gate() RETURNS trigger LANGUAGE plpgsql AS ${'$'}gate${'$'}
                    BEGIN
                        PERFORM pg_advisory_xact_lock($GATE_NAMESPACE, $GATE_KEY);
                        ${if (failAfterRelease) "RAISE EXCEPTION 'synthetic connectivity insert rollback' USING ERRCODE = '23514';" else ""}
                        RETURN NEW;
                    END;
                    ${'$'}gate${'$'}
                """.trimIndent())
                observer.executeSql("CREATE TRIGGER connectivity_concurrency_gate AFTER $events ON $table FOR EACH ROW EXECUTE FUNCTION connectivity_concurrency_gate()")
                block(gate)
            } finally {
                try {
                    gate.release()
                } finally {
                    try {
                        observer.executeSql("DROP TRIGGER IF EXISTS connectivity_concurrency_gate ON $table")
                    } finally {
                        observer.executeSql("DROP FUNCTION IF EXISTS connectivity_concurrency_gate()")
                    }
                }
            }
        }
    }

    private fun assertSeedTransaction(database: Database): Int {
        val transaction = TransactionManager.current()
        assertSame(database, transaction.db, "The lazy callback must use the creating store's transaction")
        val pid = assertNotNull(transaction.exec("SELECT pg_backend_pid()") { rows ->
            check(rows.next())
            rows.getInt(1)
        })
        val held = assertNotNull(transaction.exec("""
            SELECT count(*) FROM pg_locks AS locks
            WHERE ${lockPredicate(LOCK_NAMESPACE, LOCK_KEY)} AND locks.granted AND locks.pid = pg_backend_pid()
        """.trimIndent()) { rows ->
            check(rows.next())
            rows.getInt(1)
        })
        assertEquals(1, held, "The lazy callback must run inside pg_advisory_xact_lock")
        return pid
    }

    private suspend fun <T> Deferred<T>.awaitBounded(): T = withTimeout(WAIT_MS) { await() }

    private suspend fun stopWorkers(vararg workers: Job?) = withContext(NonCancellable) {
        workers.forEach { it?.cancel() }
        withTimeout(WAIT_MS) { workers.forEach { it?.join() } }
    }

    private fun Connection.executeSql(sql: String) {
        createStatement().use { statement ->
            statement.queryTimeout = 5
            statement.execute(sql)
        }
    }

    private fun Connection.nullableInt(sql: String): Int? = createStatement().use { statement ->
        statement.queryTimeout = 5
        statement.executeQuery(sql).use { rows -> if (rows.next()) rows.getInt(1) else null }
    }

    private fun Connection.scalarInt(sql: String): Int = assertNotNull(nullableInt(sql))

    private fun configuration(prompt: String, label: String = RETAINED_LABEL) = buildJsonObject {
        put("defaultPrompt", prompt)
        put("retainedLabel", label)
    }

    private fun lockPredicate(namespace: Int, key: Int) = """
        locks.locktype = 'advisory' AND locks.classid = $namespace::oid AND locks.objid = $key::oid
        AND locks.objsubid = 2
        AND locks.database = (SELECT oid FROM pg_database WHERE datname = current_database())
    """.trimIndent()

    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        bytes.toByteArray()
    }

    private companion object {
        const val VERSION = "1.0.0"
        const val OLD_PROMPT = "synthetic legacy prompt"
        const val PUT_PROMPT = "synthetic acknowledged PUT prompt"
        const val RETAINED_LABEL = "retained from creation"
        const val WAIT_MS = 10_000L
        const val LOCK_NAMESPACE = 1_347_570_253
        const val LOCK_KEY = 1_129_270_862
        const val GATE_NAMESPACE = 1_347_570_254
        const val GATE_KEY = 1
    }
}
