package dev.soven.prismllm.plugin.host

import dev.soven.prismllm.plugin.sdk.DiagnosticInput
import dev.soven.prismllm.plugin.sdk.DiagnosticTarget
import dev.soven.prismllm.plugin.sdk.PluginApi
import dev.soven.prismllm.plugin.sdk.PluginField
import dev.soven.prismllm.plugin.sdk.ProbeLimits
import dev.soven.prismllm.plugin.sdk.ProbeOutcome
import dev.soven.prismllm.plugin.sdk.ProbePlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Tag("unit")
internal class PluginRuntimeTest : PluginTestSupport() {
    private val target = DiagnosticTarget("channel", "channel-1", "model-1", "responses", credentialId = "key-1")
    private val input = DiagnosticInput("probe")
    private val ok = ProbeOutcome(true, 200, 10, responseText = "OK")
    private fun config(prompt: String): JsonObject = buildJsonObject { put("defaultPrompt", prompt) }

    private fun eraseManagedStorage() {
        if (!Files.exists(managedDirectory)) return
        Files.walk(managedDirectory).use { paths -> paths.filter { Files.isDirectory(it) }.forEach {
            try {
                Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------"))
            } catch (_: UnsupportedOperationException) {
                // Deletion and all non-permission assertions remain active on non-POSIX systems.
            }
        } }
        Files.walk(managedDirectory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }

    private suspend fun invoke(host: PluginRuntime, id: String = "example.probe", version: String = "1.0.0", values: JsonObject = JsonObject(emptyMap())) =
        host.execute(id, version, DiagnosticInput("probe", values), target) { ok }

    private suspend fun assertBothProbeSlotsRecover(host: PluginRuntime) = coroutineScope {
        val entered = AtomicInteger()
        val bothEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val callbacks = List(2) {
            async(Dispatchers.Default) {
                host.execute("example.probe", "1.0.0", input, target) {
                    if (entered.incrementAndGet() == 2) bothEntered.complete(Unit)
                    withTimeout(1_500) { release.await() }
                    ok
                }
            }
        }
        try {
            withTimeout(1_500) { bothEntered.await() }
        } finally {
            release.complete(Unit)
        }
        withTimeout(4_000) { callbacks.forEach { it.await() } }
        assertEquals(2, entered.get(), "Both probe slots must be available concurrently")
    }

    private suspend fun installedStore(vararg packages: PackageFixture): MemoryInstallationStore {
        publish(*packages)
        val store = MemoryInstallationStore()
        val installer = runtime(store)
        installer.initialize()
        packages.forEach {
            val installed = installer.install(it.manifest.id, it.manifest.version, it.manifest.permissions)
            installer.enable(it.manifest.id, installed.revision, it.manifest.version)
        }
        installer.close()
        return store
    }

    private fun ownedFixture(
        id: String = "example.probe",
        delayed: Boolean = false,
        delayedClose: Boolean = false,
        version: String = "1.0.0",
        configuration: List<PluginField> = defaultConfiguration(),
    ): PackageFixture = fixture(
        id = id,
        version = version,
        configuration = configuration,
        constructorPrefix = { property -> """
            System.getProperties().put("$property.loader", getClass().getClassLoader());
            ${if (delayed) """
                mark("load-ready");
                try {
                    while (!"true".equals(System.getProperty("$property.release"))) Thread.sleep(5);
                } catch (InterruptedException cancelled) {
                    mark("load-interrupted");
                    while (!"true".equals(System.getProperty("$property.release"))) {
                        try { Thread.sleep(5); } catch (InterruptedException ignored) { }
                    }
                }
                mark("load-returned");
            """.trimIndent() else ""}
        """.trimIndent() },
        closePrefix = { property -> """
            if (Thread.currentThread().getContextClassLoader() != getClass().getClassLoader()) throw new IllegalStateException("wrong close loader");
            mark("closed-with-loader");
            ${if (delayedClose) """
                mark("close-ready");
                while (!"true".equals(System.getProperty("$property.close-release"))) {
                    try { Thread.sleep(5); } catch (InterruptedException ignored) { }
                }
                mark("close-returned");
            """.trimIndent() else ""}
        """.trimIndent() },
        jarEntries = listOf(ZipFixtureEntry("fixture/owned-resource.txt", "owned".toByteArray())),
    )

    private fun loader(packaged: PackageFixture): URLClassLoader = assertNotNull(System.getProperties()["${packaged.property}.loader"] as? URLClassLoader)

    private suspend fun assertDisposed(packaged: PackageFixture) {
        awaitProperty("${packaged.property}.closed", "1")
        awaitProperty("${packaged.property}.closed-with-loader", "1")
        withTimeout(1_500) {
            while (loader(packaged).findResource("fixture/owned-resource.txt") != null) delay(5)
        }
        assertEquals(1, packaged.count("closed"))
    }

    private suspend fun initializeWithConcurrentBootstrapUpdate(
        packaged: PackageFixture,
        update: suspend (PluginRuntime) -> Unit,
    ): PluginRuntime = coroutineScope {
        val decisions = MemoryInstallationStore()
        val writer = runtime(decisions)
        writer.initialize()
        val listing = CompletableDeferred<Unit>()
        val releaseList = CompletableDeferred<Unit>()
        val host = runtime(object : PluginInstallationStore by decisions {
            override suspend fun list(): List<PluginInstallation> {
                // Bootstrap has won the insert and holds its provider, but has not published it.
                listing.complete(Unit)
                releaseList.await()
                return decisions.list()
            }
        })
        val initialization = async { host.initialize(packaged.manifest.id) }
        try {
            withTimeout(4_000) { listing.await() }
            assertEquals(1, packaged.count("constructed"))
            assertEquals(0, packaged.count("closed"))
            update(writer)
        } finally {
            releaseList.complete(Unit)
        }
        withTimeout(4_000) { initialization.await() }
        host
    }

    private fun assertNoActive(host: PluginRuntime) {
        val field = PluginRuntime::class.java.getDeclaredField("active").apply { isAccessible = true }
        assertTrue((field.get(host) as Map<*, *>).isEmpty(), "Closed/unpublished runtime must not retain active owners")
    }

    @Test
    fun `manual install configure and enable never initialize code until the next startup`(): Unit = runBlocking {
        val packaged = fixture()
        publish(packaged)
        val store = MemoryInstallationStore()
        val host = runtime(store)
        host.initialize()
        val installed = host.install(packaged.manifest.id, "1.0.0", packaged.manifest.permissions)
        assertTrue(installed.installed)
        assertFalse(installed.desiredEnabled)
        assertFalse(installed.admissionEnabled)
        assertNull(installed.activeVersion)
        val configured = host.configure(packaged.manifest.id, config("manual prompt"))
        val pending = host.enable(packaged.manifest.id, configured.revision, packaged.manifest.version)
        assertTrue(pending.requiresRestart)
        assertNull(pending.activeUi)
        assertEquals(emptyList(), host.contributions())
        assertEquals(0, packaged.count("constructed"))
        val writes = store.writes
        val replay = host.install(packaged.manifest.id, "1.0.0", packaged.manifest.permissions.reversed())
        assertEquals(pending, replay)
        assertEquals(writes, store.writes)
        host.close()
        val restarted = runtime(store)
        restarted.initialize()
        assertTrue(restarted.state(packaged.manifest.id).admissionEnabled)
        assertEquals("manual prompt", invoke(restarted).plan.prompt)
        assertEquals(1, packaged.count("constructed"))
        assertEquals(2, restarted.contributions().size)
    }

    @Test
    fun `initial install configuration is lazy and insert race preserves the winning tombstone`() : Unit = runBlocking {
        val packaged = fixture()
        publish(packaged)
        val store = MemoryInstallationStore()
        val host = runtime(store)
        host.initialize()
        val initialCalls = AtomicInteger()
        val inserted = host.install(packaged.manifest.id, "1.0.0", packaged.manifest.permissions) {
            initialCalls.incrementAndGet()
            config("initial only")
        }
        assertEquals(1, initialCalls.get())
        assertEquals(config("initial only"), inserted.configuration)

        val tombstoneCalls = AtomicInteger()
        val replay = host.install(packaged.manifest.id, "1.0.0", packaged.manifest.permissions) {
            tombstoneCalls.incrementAndGet()
            error("Existing installation must not read legacy configuration")
        }
        assertEquals(0, tombstoneCalls.get())
        assertEquals(inserted, replay)

        val racedStore = MemoryInstallationStore()
        racedStore.beforeInsert = { proposed ->
            racedStore.seed(proposed.copy(installed = false, desiredEnabled = false, configuration = config("concurrent tombstone")))
        }
        val raced = runtime(racedStore)
        raced.initialize()
        val racedCalls = AtomicInteger()
        val winner = raced.install(packaged.manifest.id, "1.0.0", packaged.manifest.permissions) {
            racedCalls.incrementAndGet()
            config("must not overwrite winner")
        }
        assertEquals(1, racedCalls.get())
        assertFalse(winner.installed)
        assertEquals(config("concurrent tombstone"), winner.configuration)
        assertEquals(config("concurrent tombstone"), racedStore.find(packaged.manifest.id)?.configuration)
    }

    @Test
    fun `initial configuration read failures do not create partial installation state`() : Unit = runBlocking {
        val packaged = fixture()
        publish(packaged)
        val store = MemoryInstallationStore()
        val host = runtime(store)
        host.initialize()
        val error = failure("PLUGIN_INITIAL_CONFIGURATION_UNAVAILABLE") {
            host.install(packaged.manifest.id, "1.0.0", packaged.manifest.permissions) { throw IllegalStateException("private settings failure") }
        }
        assertEquals(503, error.statusCode)
        assertNull(store.find(packaged.manifest.id))
        assertEquals(0, store.writes)
    }

    @Test
    fun `failed optional bootstrap configuration does not block other managed plugins or persist a partial record`(): Unit = runBlocking {
        val healthy = fixture(id = "healthy.probe")
        val healthyStore = installedStore(healthy)
        val optional = fixture(id = "prism.connectivity")
        publish(healthy, optional)
        val writes = healthyStore.writes
        val host = runtime(healthyStore)
        host.initialize(optional.manifest.id) { throw IllegalStateException("legacy configuration unavailable") }
        assertTrue(host.state(healthy.manifest.id).admissionEnabled)
        val failed = host.state(optional.manifest.id)
        assertEquals("error", failed.status)
        assertTrue(failed.loadError.orEmpty().contains("PLUGIN_BOOTSTRAP_CONFIGURATION_UNAVAILABLE"))
        assertNull(healthyStore.find(optional.manifest.id))
        assertEquals(writes, healthyStore.writes)
        assertTrue(invoke(host, healthy.manifest.id).outcome.success)
    }

    @Test
    fun `legacy bootstrap is one-time full-grant and does not reset administrator configuration`(): Unit = runBlocking {
        val packaged = fixture()
        publish(packaged)
        val store = MemoryInstallationStore()
        val host = runtime(store)
        host.initialize(packaged.manifest.id) { config("legacy prompt") }
        host.initialize(packaged.manifest.id) { error("A second initialization must not reload legacy settings") }
        val state = host.state(packaged.manifest.id)
        assertTrue(state.desiredEnabled && state.admissionEnabled)
        assertEquals(packaged.manifest.permissions, state.approvedPermissions)
        assertEquals(config("legacy prompt"), state.activeConfiguration)
        assertEquals(1, store.writes)
        assertEquals(1, packaged.count("constructed"))
        assertEquals("legacy prompt", invoke(host).plan.prompt)
    }

    @Test
    fun `bad catalogue entry cannot block official bootstrap or healthy managed installations`(): Unit = runBlocking {
        val healthy = fixture(id = "healthy.probe")
        val store = installedStore(healthy)
        val recorded = assertNotNull(store.find(healthy.manifest.id))
        val official = fixture(id = "prism.connectivity")
        val broken = fixture(id = "broken.catalogue")
        publish(healthy, official, broken)
        // Retain the catalogue's original pin while corrupting only the unrelated C package.
        Files.write(catalogDirectory.resolve(broken.fileName), broken.archive + 0)
        assertNull(store.find(official.manifest.id))
        val host = runtime(store)
        host.initialize(official.manifest.id) { config("legacy bootstrap") }
        assertTrue(host.state(healthy.manifest.id).admissionEnabled)
        assertEquals(recorded, store.find(healthy.manifest.id))
        assertTrue(host.state(official.manifest.id).admissionEnabled)
        val bad = host.catalogItems().single { it.manifest.id == broken.manifest.id }
        assertFalse(bad.compatible)
        assertTrue(bad.compatibilityReason.orEmpty().contains("PLUGIN_HASH_MISMATCH"))
        assertTrue(bad.ui.entries.isEmpty())
        assertEquals(setOf(healthy.manifest.id, official.manifest.id), host.states().map { it.id }.toSet())
        assertEquals(setOf(healthy.manifest.id, official.manifest.id), host.contributions().map { it.pluginId }.toSet())
        assertTrue(invoke(host, healthy.manifest.id).outcome.success)
        assertEquals(config("updated healthy"), host.configure(healthy.manifest.id, config("updated healthy")).activeConfiguration)
        assertEquals(0, broken.count("constructed"))
        assertNull(store.find(broken.manifest.id))
    }

    @Test
    fun `bootstrap staging failure is visible without a decision write or skipping managed plugins`(): Unit = runBlocking {
        val healthy = fixture(id = "healthy.probe")
        val store = installedStore(healthy)
        val official = fixture(id = "prism.connectivity")
        publish(healthy, official)
        val occupied = managedDirectory.resolve(official.manifest.id).resolve(official.manifest.version)
        Files.createDirectories(occupied)
        val damaged = official.archive + 0
        Files.write(occupied.resolve("package.prismplugin"), damaged)
        Files.write(occupied.resolve("plugin.jar"), official.jar)
        val writes = store.writes
        val host = runtime(store)
        host.initialize(official.manifest.id)
        assertTrue(host.state(healthy.manifest.id).admissionEnabled)
        val failed = host.state(official.manifest.id)
        assertEquals("error", failed.status)
        assertFalse(failed.installed || failed.admissionEnabled)
        assertTrue(failed.loadError.orEmpty().contains("PLUGIN_VERSION_CONFLICT"))
        assertNull(store.find(official.manifest.id))
        assertEquals(writes, store.writes)
        assertTrue(Files.readAllBytes(occupied.resolve("package.prismplugin")).contentEquals(damaged))
        assertEquals(2, host.states().size)
        assertTrue(host.contributions().all { it.pluginId == healthy.manifest.id })
        assertTrue(invoke(host, healthy.manifest.id).report.success)
        host.initialize(official.manifest.id)
        assertEquals(writes, store.writes)
        assertEquals(0, official.count("constructed"))
    }

    @Test
    fun `corrupt catalogue root leaves managed plugins usable and bootstrap errors recover by explicit install`(): Unit = runBlocking {
        val healthy = fixture(id = "healthy.probe")
        val store = installedStore(healthy)
        val official = fixture(id = "prism.connectivity")
        publish(healthy, official)
        Files.writeString(catalogDirectory.resolve("catalog.json"), "{invalid")
        val writes = store.writes
        val host = runtime(store)
        host.initialize(official.manifest.id)
        assertTrue(host.state(healthy.manifest.id).admissionEnabled)
        assertTrue(host.state(official.manifest.id).loadError.orEmpty().contains("PLUGIN_INVALID_PACKAGE"))
        assertEquals(setOf(healthy.manifest.id, official.manifest.id), host.states().map { it.id }.toSet())
        failure("PLUGIN_INVALID_PACKAGE") { host.catalogItems() }
        assertTrue(host.contributions().all { it.pluginId == healthy.manifest.id })
        assertTrue(invoke(host, healthy.manifest.id).outcome.success)
        assertEquals(writes, store.writes)
        assertNull(store.find(official.manifest.id))
        publish(healthy, official)
        host.initialize(official.manifest.id) // Initialization is complete; no implicit retry/decision.
        assertNull(store.find(official.manifest.id))
        val installed = host.install(official.manifest.id, official.manifest.version, official.manifest.permissions)
        assertTrue(installed.installed)
        assertFalse(installed.desiredEnabled || installed.admissionEnabled)
        assertNull(installed.loadError)
        assertEquals(1, host.states().count { it.id == official.manifest.id })
        assertEquals(0, official.count("constructed"))
    }

    @Test
    fun `invalid optional bootstrap package records an actionable error without disabling healthy managed code`(): Unit = runBlocking {
        val healthy = fixture(id = "healthy.probe")
        val store = installedStore(healthy)
        val official = fixture(id = "prism.connectivity")
        publish(healthy, official)
        Files.write(catalogDirectory.resolve(official.fileName), official.archive + 0)
        val writes = store.writes
        val host = runtime(store)
        host.initialize(official.manifest.id)
        assertTrue(host.state(healthy.manifest.id).admissionEnabled)
        val failed = host.state(official.manifest.id)
        assertEquals("error", failed.status)
        assertTrue(failed.loadError.orEmpty().contains("PLUGIN_HASH_MISMATCH"))
        assertFalse(host.catalogItems().single { it.manifest.id == official.manifest.id }.compatible)
        assertEquals(writes, store.writes)
        assertNull(store.find(official.manifest.id))
        assertFalse(Files.exists(managedDirectory.resolve(official.manifest.id)))
        assertTrue(invoke(host, healthy.manifest.id).report.success)
    }

    @Test
    fun `bootstrap disposes provider identity mismatch and falls back to the next older candidate`(): Unit = runBlocking {
        val older = fixture(version = "1.8.0")
        val newer = fixture(version = "2.0.0", providerVersion = "9.9.9")
        publish(older, newer)
        val store = MemoryInstallationStore()
        val host = runtime(store)

        host.initialize(older.manifest.id) { config("legacy fallback") }

        val state = host.state(older.manifest.id)
        assertEquals("1.8.0", state.installedVersion)
        assertEquals("1.8.0", state.activeVersion)
        assertEquals(config("legacy fallback"), state.configuration)
        assertEquals(1, newer.count("constructed"))
        awaitProperty("${newer.property}.closed", "1")
        assertEquals(1, newer.count("closed"))
        assertEquals(1, older.count("constructed"))
        assertEquals(0, older.count("closed"))
        assertEquals("1.8.0", store.find(older.manifest.id)?.version)
    }

    @Test
    fun `bootstrap rejects incomplete configuration before writing and falls back to the older candidate`(): Unit = runBlocking {
        val older = fixture()
        val newer = ownedFixture(
            version = "2.0.0",
            configuration = defaultConfiguration() + PluginField("newRequired", "New required", "text", required = true),
        )
        publish(older, newer)
        val store = MemoryInstallationStore()
        val host = runtime(store)
        val legacy = config("complete only for the older candidate")

        host.initialize(newer.manifest.id) { legacy }

        val record = assertNotNull(store.find(newer.manifest.id))
        assertEquals(1, store.writes)
        assertEquals(older.manifest.version, record.version)
        assertEquals(older.sha256, record.sha256)
        assertEquals(legacy, record.configuration)
        assertEquals(older.manifest.version, host.state(newer.manifest.id).activeVersion)
        assertEquals("complete only for the older candidate", invoke(host).plan.prompt)
        assertEquals(1, newer.count("constructed"))
        assertDisposed(newer)
        host.close()
        assertEquals(1, newer.count("closed"))
    }

    @Test
    fun `bootstrap chooses the latest compatible semantic version deterministically`(): Unit = runBlocking {
        val earlier = fixture(version = "1.9.0")
        val later = fixture(version = "1.10.0")
        val preview = fixture(version = "2.0.0-rc.1")
        val incompatible = fixture(version = "2.0.0", apiVersion = 2)
        publish(incompatible, earlier, preview, later)
        val host = runtime()
        host.initialize(earlier.manifest.id)
        assertEquals("2.0.0-rc.1", host.state(earlier.manifest.id).activeVersion)
        assertEquals(0, incompatible.count("constructed"))
    }

    @Test
    fun `disable closes admission now and enabling a loaded class remains pending until restart`(): Unit = runBlocking {
        val packaged = fixture()
        publish(packaged)
        val store = MemoryInstallationStore()
        val host = runtime(store)
        host.initialize(packaged.manifest.id)
        val disabled = host.disable(packaged.manifest.id)
        assertFalse(disabled.admissionEnabled)
        assertFalse(disabled.requiresRestart)
        assertEquals("1.0.0", disabled.activeVersion)
        assertNull(disabled.activeUi)
        failure("PLUGIN_DISABLED") { invoke(host) }
        val enabled = host.enable(packaged.manifest.id, disabled.revision, packaged.manifest.version, packaged.manifest.permissions)
        assertTrue(enabled.desiredEnabled && enabled.requiresRestart)
        assertFalse(enabled.admissionEnabled)
        failure("PLUGIN_DISABLED") { invoke(host) }
        host.close()
        val restarted = runtime(store)
        restarted.initialize()
        assertTrue(restarted.state(packaged.manifest.id).admissionEnabled)
        assertEquals(2, packaged.count("constructed"))
    }

    @Test
    fun `uninstall keeps a tombstone config and selection and suppresses bootstrap after restart`(): Unit = runBlocking {
        val packaged = fixture()
        publish(packaged)
        val store = MemoryInstallationStore()
        val host = runtime(store)
        host.initialize(packaged.manifest.id) { config("retain me") }
        val tombstone = host.uninstall(packaged.manifest.id)
        assertFalse(tombstone.installed || tombstone.desiredEnabled || tombstone.admissionEnabled)
        assertEquals("uninstalled", tombstone.status)
        assertEquals("1.0.0", tombstone.installedVersion)
        assertEquals(config("retain me"), tombstone.configuration)
        assertNull(tombstone.activeUi)
        failure("PLUGIN_DISABLED") { invoke(host) }
        host.close()
        val restarted = runtime(store)
        restarted.initialize(packaged.manifest.id) { error("A tombstone must not load legacy settings") }
        assertFalse(restarted.state(packaged.manifest.id).installed)
        assertEquals(1, packaged.count("constructed"))
        val restored = restarted.install(packaged.manifest.id, "1.0.0", packaged.manifest.permissions)
        assertTrue(restored.installed)
        assertFalse(restored.desiredEnabled)
        assertEquals(config("retain me"), restored.configuration)
    }

    @Test
    fun `a concurrent tombstone wins insert-if-absent bootstrap`(): Unit = runBlocking {
        val packaged = fixture()
        publish(packaged)
        val store = MemoryInstallationStore()
        store.beforeInsert = { proposed -> store.seed(proposed.copy(installed = false, desiredEnabled = false, configuration = config("concurrent tombstone"))) }
        val host = runtime(store)
        host.initialize(packaged.manifest.id)
        val state = host.state(packaged.manifest.id)
        assertFalse(state.installed)
        assertEquals(config("concurrent tombstone"), state.configuration)
        awaitProperty("${packaged.property}.closed", "1")
        assertEquals(1, packaged.count("constructed"))
        assertEquals(1, packaged.count("closed"))
        assertFalse(store.find(packaged.manifest.id)?.installed ?: true)
    }

    @Test
    fun `concurrent upgrade before startup listing supersedes the pending bootstrap provider`(): Unit = runBlocking {
        val original = ownedFixture()
        val replacement = ownedFixture(version = "2.0.0")
        publish(original)
        val host = initializeWithConcurrentBootstrapUpdate(original) { writer ->
            publish(original, replacement)
            writer.install(replacement.manifest.id, replacement.manifest.version, replacement.manifest.permissions)
            writer.configure(replacement.manifest.id, config("concurrent upgrade"))
        }

        assertDisposed(original)
        val state = host.state(original.manifest.id)
        assertEquals(replacement.manifest.version, state.activeVersion)
        assertTrue(state.admissionEnabled)
        assertEquals("concurrent upgrade", invoke(host, version = replacement.manifest.version).plan.prompt)
        assertEquals(1, replacement.count("constructed"))
        assertEquals(0, replacement.count("closed"))
        host.close()
        assertDisposed(replacement)
        assertEquals(1, original.count("closed"))
    }

    @Test
    fun `concurrent tombstone before startup listing disposes the pending bootstrap provider`(): Unit = runBlocking {
        val original = ownedFixture()
        publish(original)
        val host = initializeWithConcurrentBootstrapUpdate(original) { it.uninstall(original.manifest.id) }

        assertDisposed(original)
        val state = host.state(original.manifest.id)
        assertEquals("uninstalled", state.status)
        assertNull(state.activeVersion)
        assertFalse(state.admissionEnabled)
        assertNoActive(host)
        host.close()
        assertEquals(1, original.count("closed"))
    }

    @Test
    fun `concurrent disable before startup listing disposes the pending bootstrap provider`(): Unit = runBlocking {
        val original = ownedFixture()
        publish(original)
        val host = initializeWithConcurrentBootstrapUpdate(original) { it.disable(original.manifest.id) }

        assertDisposed(original)
        val state = host.state(original.manifest.id)
        assertEquals("disabled", state.status)
        assertNull(state.activeVersion)
        assertFalse(state.admissionEnabled)
        assertNoActive(host)
        host.close()
        assertEquals(1, original.count("closed"))
    }

    @Test
    fun `pending upgrade exposes desired fields but never swaps active UI or active config`(): Unit = runBlocking {
        val previous = fixture()
        publish(previous)
        val store = MemoryInstallationStore()
        val host = runtime(store)
        host.initialize(previous.manifest.id) { config("old config") }
        val replacement = fixture(version = "2.0.0", configKey = "futurePrompt", configuration = listOf(
            PluginField("futurePrompt", "Future prompt", "textarea", required = true, defaultValue = JsonPrimitive("new default"), maxLength = 4_000),
        ))
        publish(previous, replacement)
        failure("PLUGIN_PERMISSION_REQUIRED") { host.install(previous.manifest.id, "2.0.0", listOf(PluginApi.CHANNEL_PROBE)) }
        assertEquals("1.0.0", host.state(previous.manifest.id).installedVersion)
        val pending = host.install(previous.manifest.id, "2.0.0", replacement.manifest.permissions)
        assertTrue(pending.requiresRestart && pending.desiredEnabled && pending.admissionEnabled)
        assertEquals("2.0.0", pending.installedVersion)
        assertEquals("1.0.0", pending.activeVersion)
        assertEquals(previous.ui, pending.activeUi)
        assertEquals(listOf("futurePrompt"), pending.configurationFields.map { it.key })
        val changed = host.configure(previous.manifest.id, buildJsonObject { put("futurePrompt", "pending config") })
        assertEquals(config("old config"), changed.activeConfiguration)
        assertEquals("old config", invoke(host).plan.prompt)
        failure("PLUGIN_ACTIVE_VERSION_CONFLICT") { invoke(host, version = "2.0.0") }
        assertTrue(host.contributions().all { it.activeVersion == "1.0.0" })
        assertEquals(0, replacement.count("constructed"))
        host.close()
        val restarted = runtime(store)
        restarted.initialize()
        assertEquals(replacement.ui, restarted.state(previous.manifest.id).activeUi)
        assertEquals("pending config", invoke(restarted, version = "2.0.0").plan.prompt)
    }

    @Test
    fun `active permission rechecks use executable grants while an upgrade is pending`(): Unit = runBlocking {
        val previous = fixture()
        publish(previous)
        val host = runtime()
        host.initialize(previous.manifest.id)
        val replacement = fixture(
            version = "2.0.0",
            permissions = listOf(PluginApi.CHANNEL_PROBE),
            ui = defaultUi("2.0.0").let { it.copy(entries = it.entries.filter { entry -> entry.id == "channel" }) },
        )
        publish(previous, replacement)
        val pending = host.install(previous.manifest.id, "2.0.0", replacement.manifest.permissions)
        assertEquals(listOf(PluginApi.CHANNEL_PROBE), pending.approvedPermissions)
        host.requireActivePermission(previous.manifest.id, "1.0.0", PluginApi.OAUTH_ACCOUNT_PROBE)
        failure("PLUGIN_ACTIVE_VERSION_CONFLICT") {
            host.requireActivePermission(previous.manifest.id, "2.0.0", PluginApi.OAUTH_ACCOUNT_PROBE)
        }
        host.disable(previous.manifest.id)
        failure("PLUGIN_DISABLED") {
            host.requireActivePermission(previous.manifest.id, "1.0.0", PluginApi.OAUTH_ACCOUNT_PROBE)
        }
    }

    @Test
    fun `active permission rechecks observe revocation for the same executable selection`(): Unit = runBlocking {
        val packaged = fixture()
        publish(packaged)
        val store = MemoryInstallationStore()
        val host = runtime(store)
        host.initialize(packaged.manifest.id)
        val record = assertNotNull(store.find(packaged.manifest.id))
        store.seed(record.copy(approvedPermissions = listOf(PluginApi.CHANNEL_PROBE), revision = record.revision + 1))
        failure("PLUGIN_DISABLED") {
            host.requireActivePermission(packaged.manifest.id, "1.0.0", PluginApi.OAUTH_ACCOUNT_PROBE)
        }
        assertFalse(host.state(packaged.manifest.id).admissionEnabled)
    }

    @Test
    fun `reviewed install digest rejects a changed same-version catalogue candidate before persistence`(): Unit = runBlocking {
        val reviewed = fixture()
        publish(reviewed)
        val store = MemoryInstallationStore()
        val host = runtime(store)
        host.initialize()
        val digest = host.catalogItems().single().sha256
        val replacement = fixture(version = reviewed.manifest.version)
        publish(replacement)
        assertFalse(digest == replacement.sha256)
        val conflict = failure("PLUGIN_CATALOG_CONFLICT") {
            host.install(reviewed.manifest.id, reviewed.manifest.version, reviewed.manifest.permissions, digest)
        }
        assertEquals(409, conflict.statusCode)
        assertNull(store.find(reviewed.manifest.id))
        assertEquals(0, store.writes)
        assertEquals(0, replacement.count("constructed"))
        val installed = host.install(replacement.manifest.id, replacement.manifest.version, replacement.manifest.permissions, replacement.sha256)
        assertEquals(0L, installed.revision)
        assertEquals(replacement.sha256, store.find(replacement.manifest.id)?.sha256)
    }

    @Test
    fun `sequential same-version drafts compare the original client revision including no-op writes`(): Unit = runBlocking {
        val packaged = fixture()
        publish(packaged)
        val store = MemoryInstallationStore()
        val firstHost = runtime(store)
        firstHost.initialize(packaged.manifest.id) { config("original") }
        val secondHost = runtime(store)
        secondHost.initialize()
        val baseline = firstHost.state(packaged.manifest.id)
        val winner = firstHost.configure(packaged.manifest.id, config("winner"), baseline.revision, packaged.manifest.version)
        assertEquals(baseline.revision + 1, winner.revision)
        val writes = store.writes
        for (draft in listOf(config("stale loser"), winner.configuration)) {
            val conflict = failure("PLUGIN_REVISION_CONFLICT") {
                secondHost.configure(packaged.manifest.id, draft, baseline.revision, packaged.manifest.version)
            }
            assertEquals(409, conflict.statusCode)
        }
        failure("PLUGIN_REVISION_CONFLICT") {
            secondHost.configure(packaged.manifest.id, config("wrong selection"), winner.revision, "2.0.0")
        }
        assertEquals(writes, store.writes)
        assertEquals(config("winner"), secondHost.state(packaged.manifest.id).configuration)
        assertEquals("winner", invoke(firstHost).plan.prompt)
    }

    @Test
    fun `field updates merge the configuration paired with their CAS revision`(): Unit = runBlocking {
        val packaged = fixture(configuration = defaultConfiguration() + PluginField("other", "Other", "text"))
        publish(packaged)
        val store = MemoryInstallationStore()
        val host = runtime(store)
        host.initialize(packaged.manifest.id) { buildJsonObject { put("defaultPrompt", "old"); put("other", "original") } }
        val record = assertNotNull(store.find(packaged.manifest.id))
        store.seed(record.copy(configuration = buildJsonObject { put("defaultPrompt", "old"); put("other", "concurrent") }, revision = record.revision + 1))
        val updated = host.configureFields(packaged.manifest.id, config("new"))
        assertEquals(buildJsonObject { put("defaultPrompt", "new"); put("other", "concurrent") }, updated.configuration)
        assertEquals(record.revision + 2, store.find(packaged.manifest.id)?.revision)
        val beforeConflict = assertNotNull(store.find(packaged.manifest.id))
        store.beforeSave = {
            store.seed(beforeConflict.copy(configuration = buildJsonObject { put("defaultPrompt", "new"); put("other", "winner") }, revision = beforeConflict.revision + 1))
        }
        failure("PLUGIN_REVISION_CONFLICT") { host.configureFields(packaged.manifest.id, config("loser")) }
        store.beforeSave = null
        assertEquals(buildJsonObject { put("defaultPrompt", "new"); put("other", "winner") }, host.state(packaged.manifest.id).configuration)
        assertEquals(2, store.writes)
    }

    @Test
    fun `same-version configuration updates are immediate but each invocation gets a stable snapshot`(): Unit = runBlocking {
        val packaged = fixture(planPrefix = { property -> """
            System.setProperty("$property.ready", "true");
            while (!"true".equals(System.getProperty("$property.release"))) {
                try { Thread.sleep(5); } catch (InterruptedException e) { throw new RuntimeException(e); }
            }
        """.trimIndent() })
        publish(packaged)
        val host = runtime()
        host.initialize(packaged.manifest.id) { config("old snapshot") }
        coroutineScope {
            val invocation = async(Dispatchers.Default) { invoke(host) }
            try {
                awaitProperty("${packaged.property}.ready")
                val changed = host.configure(packaged.manifest.id, config("new snapshot"))
                assertEquals(config("new snapshot"), changed.activeConfiguration)
            } finally {
                System.setProperty("${packaged.property}.release", "true")
            }
            val execution = withTimeout(4_000) { invocation.await() }
            assertEquals("old snapshot", execution.plan.prompt)
            assertEquals("old snapshot", execution.report.text)
        }
        assertEquals("new snapshot", invoke(host).plan.prompt)
    }

    @Test
    fun `disable during plan prevents the credential-preparing callback`(): Unit = runBlocking {
        val packaged = fixture(planPrefix = { property -> """
            System.setProperty("$property.ready", "true");
            while (!"true".equals(System.getProperty("$property.release"))) {
                try { Thread.sleep(5); } catch (InterruptedException e) { throw new RuntimeException(e); }
            }
        """.trimIndent() })
        publish(packaged)
        val host = runtime()
        host.initialize(packaged.manifest.id)
        val probes = AtomicInteger()
        coroutineScope {
            val invocation = async(Dispatchers.Default) { runCatching { host.execute(packaged.manifest.id, "1.0.0", input, target) { probes.incrementAndGet(); ok } } }
            try {
                awaitProperty("${packaged.property}.ready")
                host.disable(packaged.manifest.id)
            } finally {
                System.setProperty("${packaged.property}.release", "true")
            }
            val error = withTimeout(4_000) { invocation.await() }.exceptionOrNull() as PluginHostException
            assertEquals("PLUGIN_DISABLED", error.code)
        }
        assertEquals(0, probes.get())
        assertEquals(0, packaged.count("reported"))
    }

    @Test
    fun `CAS failures never replace active config or silently retry and disable remains fail-closed`(): Unit = runBlocking {
        val packaged = fixture()
        publish(packaged)
        val store = MemoryInstallationStore()
        val host = runtime(store)
        host.initialize(packaged.manifest.id) { config("committed") }
        store.failNextSave.set(true)
        failure("PLUGIN_REVISION_CONFLICT") { host.configure(packaged.manifest.id, config("uncommitted")) }
        assertEquals(config("committed"), host.state(packaged.manifest.id).activeConfiguration)
        assertEquals(1, store.writes)
        host.configure(packaged.manifest.id, config("next revision"))
        assertEquals(1L, store.find(packaged.manifest.id)?.revision)
        store.failNextSave.set(true)
        failure("PLUGIN_REVISION_CONFLICT") { host.disable(packaged.manifest.id) }
        val state = host.state(packaged.manifest.id)
        assertTrue(state.desiredEnabled && state.requiresRestart)
        assertFalse(state.admissionEnabled)
        failure("PLUGIN_DISABLED") { invoke(host) }
        assertEquals(2, store.writes)
    }

    @Test
    fun `explicit complete grants are required and startup will not load unapproved code`(): Unit = runBlocking {
        val packaged = fixture()
        publish(packaged)
        val store = MemoryInstallationStore()
        val host = runtime(store)
        host.initialize()
        failure("PLUGIN_PERMISSION_REQUIRED") { host.install(packaged.manifest.id, "1.0.0", emptyList()) }
        failure("PLUGIN_INVALID_GRANTS") { host.install(packaged.manifest.id, "1.0.0", packaged.manifest.permissions + "arbitrary.permission") }
        host.install(packaged.manifest.id, "1.0.0", packaged.manifest.permissions)
        val record = assertNotNull(store.find(packaged.manifest.id))
        store.seed(record.copy(desiredEnabled = true, approvedPermissions = emptyList()))
        host.close()
        val restarted = runtime(store)
        restarted.initialize()
        assertEquals("error", restarted.state(packaged.manifest.id).status)
        assertNull(restarted.state(packaged.manifest.id).activeVersion)
        assertEquals(0, packaged.count("constructed"))
    }

    @Test
    fun `stale versions unknown actions contexts endpoint input and mismatched target choices fail before plan`(): Unit = runBlocking {
        val packaged = fixture()
        publish(packaged)
        val host = runtime()
        host.initialize(packaged.manifest.id)
        val probes = AtomicInteger()
        suspend fun execute(invocation: DiagnosticInput = input, selected: DiagnosticTarget = target, version: String = "1.0.0") =
            host.execute(packaged.manifest.id, version, invocation, selected) { probes.incrementAndGet(); ok }
        failure("PLUGIN_ACTIVE_VERSION_CONFLICT") { execute(version = "0.0.1") }
        failure("PLUGIN_ACTION_NOT_AVAILABLE") { execute(DiagnosticInput("not-declared")) }
        failure { execute(selected = target.copy(kind = "arbitrary")) }
        failure("PLUGIN_INVALID_INPUT") { execute(DiagnosticInput("probe", buildJsonObject { put("endpoint", "https://not-allowed.invalid") })) }
        failure("PLUGIN_INVALID_INPUT") { execute(DiagnosticInput("probe", buildJsonObject { put("model", "not-authorized") })) }
        failure("PLUGIN_INVALID_INPUT") { execute(DiagnosticInput("probe", buildJsonObject { put("protocol", "not-authorized") })) }
        failure("PLUGIN_INVALID_INPUT") { execute(DiagnosticInput("probe", buildJsonObject { put("keyId", "not-authorized") })) }
        failure("PLUGIN_INVALID_INPUT") { execute(DiagnosticInput("probe", buildJsonObject { put("scope", "all") }), target.copy(kind = "oauth-account", credentialId = null)) }
        assertEquals(0, packaged.count("planned"))
        assertEquals(0, probes.get())
        val account = target.copy(kind = "oauth-account", id = "account-1", credentialId = null)
        assertEquals(account, execute(selected = account).target)
    }

    @Test
    fun `configured required field falling outside new schema falls back to valid default or remains incomplete`() : Unit = runBlocking {
        val oldVersion = fixture(configuration = listOf(
            PluginField("defaultPrompt", "Default prompt", "textarea", required = true, defaultValue = JsonPrimitive("old default"), maxLength = 4_000),
            PluginField("newRequired", "New required", "text"),
        ))
        publish(oldVersion)
        val store = MemoryInstallationStore()
        val original = runtime(store)
        original.initialize()
        original.install(oldVersion.manifest.id, "1.0.0", oldVersion.manifest.permissions)
        original.configure(oldVersion.manifest.id, buildJsonObject { put("defaultPrompt", "valid old value"); put("newRequired", "  ") })
        val nextVersion = fixture(version = "2.0.0", configuration = listOf(
            PluginField("defaultPrompt", "Default prompt", "textarea", required = true, defaultValue = JsonPrimitive("new"), maxLength = 3),
            PluginField("newRequired", "New required", "text", required = true),
        ))
        publish(oldVersion, nextVersion)
        val installed = original.install(oldVersion.manifest.id, "2.0.0", nextVersion.manifest.permissions) {
            error("An upgrade must not reload legacy settings")
        }
        assertEquals(JsonPrimitive("new"), installed.configuration["defaultPrompt"])
        assertNull(installed.configuration["newRequired"])
        assertFalse(installed.desiredEnabled)
        failure("PLUGIN_INVALID_CONFIGURATION") { original.enable(oldVersion.manifest.id, installed.revision, nextVersion.manifest.version) }
        val incomplete = assertNotNull(store.find(oldVersion.manifest.id))
        store.seed(incomplete.copy(desiredEnabled = true))
        original.close()
        val incompleteHost = runtime(store)
        incompleteHost.initialize()
        assertFalse(incompleteHost.state(oldVersion.manifest.id).admissionEnabled)
        assertNull(incompleteHost.state(oldVersion.manifest.id).activeVersion)
        assertEquals(0, nextVersion.count("constructed"))
        incompleteHost.disable(oldVersion.manifest.id)
        val configured = incompleteHost.configure(oldVersion.manifest.id, buildJsonObject { put("defaultPrompt", "new"); put("newRequired", "repaired") })
        incompleteHost.enable(oldVersion.manifest.id, configured.revision, nextVersion.manifest.version)
        incompleteHost.close()
        val repaired = runtime(store)
        repaired.initialize()
        assertTrue(repaired.state(oldVersion.manifest.id).admissionEnabled)
        assertEquals("new", invoke(repaired, version = "2.0.0").plan.prompt)
        assertEquals(1, nextVersion.count("constructed"))
    }

    @Test
    fun `configuration enforces declared scalar schema and defaults without secret fields`(): Unit = runBlocking {
        val packaged = fixture(configuration = defaultConfiguration() + listOf(
            PluginField("flag", "Flag", "boolean", defaultValue = JsonPrimitive(false)),
            PluginField("count", "Count", "number", defaultValue = JsonPrimitive(2), min = 1.0, max = 3.0),
        ))
        publish(packaged)
        val host = runtime()
        host.initialize(packaged.manifest.id)
        val invalid = listOf(
            buildJsonObject { put("defaultPrompt", "x".repeat(4_001)) },
            buildJsonObject { put("defaultPrompt", "  ") },
            buildJsonObject { put("flag", "true") },
            buildJsonObject { put("count", 4) },
            buildJsonObject { put("count", "2") },
            buildJsonObject { put("baseUrl", "https://not-allowed.invalid") },
            buildJsonObject { put("flag", buildJsonObject { put("nested", true) }) },
        )
        invalid.forEach { values -> failure("PLUGIN_INVALID_CONFIGURATION") { host.configure(packaged.manifest.id, values) } }
        assertEquals("请只回复 OK", invoke(host).plan.prompt)
        val valid = host.configure(packaged.manifest.id, buildJsonObject { put("defaultPrompt", "valid"); put("count", 3); put("flag", true) })
        assertEquals(JsonPrimitive(true), valid.activeConfiguration?.get("flag"))
    }

    @Test
    fun `configuration that exceeds the UTF-8 size limit after defaults is never saved`(): Unit = runBlocking {
        // Vary the multibyte text so the fixture manifest also meets the archive compression bound.
        val text = buildString { repeat(4_000) { append(('一'.code + it).toChar()) } }
        val defaults = List(12) { PluginField("default$it", "Default", "textarea", defaultValue = JsonPrimitive(text)) }
        val supplied = List(12) { PluginField("supplied$it", "Supplied", "textarea") }
        val packaged = fixture(configuration = defaultConfiguration() + defaults + supplied)
        publish(packaged)
        val id = packaged.manifest.id
        val store = MemoryInstallationStore()
        val host = runtime(store)
        host.initialize(id)

        val valid = host.configure(id, buildJsonObject { put("supplied0", text) })
        assertEquals(JsonPrimitive(text), valid.activeConfiguration?.get("default0"))
        assertEquals(JsonPrimitive(text), valid.activeConfiguration?.get("supplied0"))
        val before = assertNotNull(store.find(id))
        val writes = store.writes

        val values = JsonObject(supplied.associate { it.key to JsonPrimitive(text) })
        val merged = JsonObject(before.configuration + values).toString()
        assertTrue(values.toString().toByteArray(Charsets.UTF_8).size <= PluginValidation.MAX_METADATA_BYTES)
        assertTrue(merged.length <= PluginValidation.MAX_METADATA_BYTES)
        assertTrue(merged.toByteArray(Charsets.UTF_8).size > PluginValidation.MAX_METADATA_BYTES)
        failure("PLUGIN_INVALID_CONFIGURATION") { host.configure(id, values) }

        assertEquals(writes, store.writes)
        assertEquals(before, store.find(id))
        val state = host.state(id)
        assertTrue(state.admissionEnabled)
        assertEquals(before.configuration, state.activeConfiguration)
        assertEquals("请只回复 OK", invoke(host).plan.prompt)
    }

    @Test
    fun `oversized invocation prompts fail before plan probe and report`(): Unit = runBlocking {
        val packaged = fixture()
        publish(packaged)
        val host = runtime()
        host.initialize(packaged.manifest.id)
        val probes = AtomicInteger()
        val values = buildJsonObject { put("prompt", "  " + "x".repeat(4_001) + "  ") }
        failure("PLUGIN_INVALID_INPUT") {
            host.execute(packaged.manifest.id, "1.0.0", DiagnosticInput("probe", values), target, ProbeLimits(maxPromptChars = 20_000)) {
                probes.incrementAndGet()
                ok
            }
        }
        assertEquals(0, packaged.count("planned"))
        assertEquals(0, probes.get())
        assertEquals(0, packaged.count("reported"))
    }

    @Test
    fun `invocation prompts obey tighter host and field limits after trimming`(): Unit = runBlocking {
        val ui = defaultUi().let { descriptor -> descriptor.copy(entries = descriptor.entries.map { entry ->
            entry.copy(action = entry.action.copy(fields = entry.action.fields.map { field ->
                if (field.key == "prompt") field.copy(maxLength = 16) else field
            }))
        }) }
        val packaged = fixture(ui = ui)
        publish(packaged)
        val host = runtime()
        host.initialize(packaged.manifest.id)
        val probes = AtomicInteger()
        suspend fun execute(prompt: String, limits: ProbeLimits) = host.execute(
            packaged.manifest.id, "1.0.0", DiagnosticInput("probe", buildJsonObject { put("prompt", prompt) }), target, limits,
        ) { probes.incrementAndGet(); ok }
        val cases = listOf(ProbeLimits(maxPromptChars = 8) to 8, ProbeLimits(maxPromptChars = 32) to 16)
        cases.forEach { (limits, maximum) ->
            failure("PLUGIN_INVALID_INPUT") { execute("  " + "测".repeat(maximum + 1) + "  ", limits) }
        }
        assertEquals(0, packaged.count("planned"))
        assertEquals(0, probes.get())
        assertEquals(0, packaged.count("reported"))
        cases.forEach { (limits, maximum) ->
            val prompt = "测".repeat(maximum)
            assertEquals(prompt, execute("  $prompt  ", limits).plan.prompt)
        }
        assertEquals(2, packaged.count("planned"))
        assertEquals(2, probes.get())
        assertEquals(2, packaged.count("reported"))
    }

    @Test
    fun `host trims a boundary prompt and clamps token and timeout limits while keeping upstream 401 in outcome`(): Unit = runBlocking {
        val packaged = fixture(
            planPrefix = { "if (prompt.length() != 4000) throw new IllegalStateException(\"Input prompt must be trimmed before planning\");" },
            reportPrefix = "if (outcome.getResponseText() == null || outcome.getResponseText().length() != 16000 || !outcome.getResponseText().endsWith(\"Z\")) throw new IllegalStateException(\"NUL cleanup must precede response truncation\");",
        )
        publish(packaged)
        val host = runtime()
        host.initialize(packaged.manifest.id)
        val probes = AtomicInteger()
        var actualPlan: ProbePlan? = null
        val prompt = "测".repeat(4_000)
        val values = buildJsonObject { put("prompt", "  $prompt  ") }
        val execution = host.execute(packaged.manifest.id, "1.0.0", DiagnosticInput("probe", values), target, ProbeLimits(20_000, 2_048, 90_000)) {
            actualPlan = it
            probes.incrementAndGet()
            ProbeOutcome(false, 401, 10, responseText = "x".repeat(15_999) + "\u0000Z", errorMessage = "upstream authentication failed")
        }
        assertEquals(16_000, execution.outcome.responseText?.length)
        assertEquals('Z', execution.outcome.responseText?.last())
        assertFalse(execution.outcome.responseText.orEmpty().contains('\u0000'))
        assertEquals(prompt, execution.plan.prompt)
        assertEquals(128, execution.plan.maxOutputTokens)
        assertEquals(45_000L, execution.plan.timeoutMs)
        assertEquals(actualPlan, execution.plan)
        assertEquals(401, execution.outcome.statusCode)
        assertFalse(execution.report.success)
        assertEquals(1, probes.get())
        assertEquals(1, packaged.count("planned"))
        assertEquals(1, packaged.count("reported"))
    }

    @Test
    fun `oversized plugin plans fail before probe and report while boundary plans retain trimming`(): Unit = runBlocking {
        val packaged = fixture(planPrefix = { property -> "prompt = System.getProperty(\"$property.plan-prompt\");" })
        publish(packaged)
        val host = runtime()
        host.initialize(packaged.manifest.id)
        val probes = AtomicInteger()
        for (maximum in listOf(4_000, 16)) {
            System.setProperty("${packaged.property}.plan-prompt", "  " + "测".repeat(maximum + 1) + "  ")
            failure("PLUGIN_INVALID_PLAN") {
                host.execute(packaged.manifest.id, "1.0.0", input, target, ProbeLimits(maxPromptChars = maximum)) {
                    probes.incrementAndGet()
                    ok
                }
            }
        }
        assertEquals(2, packaged.count("planned"))
        assertEquals(0, probes.get())
        assertEquals(0, packaged.count("reported"))
        val prompt = "测".repeat(4_000)
        System.setProperty("${packaged.property}.plan-prompt", "  $prompt  ")
        val execution = host.execute(packaged.manifest.id, "1.0.0", input, target) {
            probes.incrementAndGet()
            assertEquals(prompt, it.prompt)
            ok
        }
        assertEquals(prompt, execution.plan.prompt)
        assertEquals(3, packaged.count("planned"))
        assertEquals(1, probes.get())
        assertEquals(1, packaged.count("reported"))
    }

    @Test
    fun `returned plans honor the active prompt field limit and use the host limit when prompt is absent`(): Unit = runBlocking {
        val limitedUi = defaultUi().let { descriptor -> descriptor.copy(entries = descriptor.entries.map { entry ->
            entry.copy(action = entry.action.copy(fields = entry.action.fields.map { field ->
                if (field.key == "prompt") field.copy(maxLength = 16) else field
            }))
        }) }
        val fieldLimited = fixture(
            id = "field.limited",
            ui = limitedUi,
            planPrefix = { property ->
                "if (context.getLimits().getMaxPromptChars() != 16) throw new IllegalStateException(\"Wrong effective prompt limit\"); " +
                    "prompt = System.getProperty(\"$property.plan-prompt\");"
            },
        )
        val noPromptUi = defaultUi().let { descriptor -> descriptor.copy(entries = descriptor.entries.map { entry ->
            entry.copy(action = entry.action.copy(fields = entry.action.fields.filterNot { it.key == "prompt" }))
        }) }
        val withoutPrompt = fixture(
            id = "without.prompt",
            ui = noPromptUi,
            planPrefix = { property ->
                "if (context.getLimits().getMaxPromptChars() != 4000) throw new IllegalStateException(\"Wrong effective prompt limit\"); " +
                    "prompt = System.getProperty(\"$property.plan-prompt\");"
            },
        )
        val store = installedStore(fieldLimited, withoutPrompt)
        val host = runtime(store)
        host.initialize()

        val fieldProbes = AtomicInteger()
        System.setProperty("${fieldLimited.property}.plan-prompt", "  ${"测".repeat(17)}  ")
        failure("PLUGIN_INVALID_PLAN") {
            host.execute(fieldLimited.manifest.id, "1.0.0", input, target) { fieldProbes.incrementAndGet(); ok }
        }
        assertEquals(1, fieldLimited.count("planned"))
        assertEquals(0, fieldProbes.get())
        assertEquals(0, fieldLimited.count("reported"))

        val fieldBoundary = "测".repeat(16)
        System.setProperty("${fieldLimited.property}.plan-prompt", "  $fieldBoundary  ")
        val fieldExecution = host.execute(fieldLimited.manifest.id, "1.0.0", input, target) { fieldProbes.incrementAndGet(); ok }
        assertEquals(fieldBoundary, fieldExecution.plan.prompt)
        assertEquals(1, fieldProbes.get())
        assertEquals(1, fieldLimited.count("reported"))

        val hostProbes = AtomicInteger()
        System.setProperty("${withoutPrompt.property}.plan-prompt", "  ${"测".repeat(4_001)}  ")
        failure("PLUGIN_INVALID_PLAN") {
            host.execute(withoutPrompt.manifest.id, "1.0.0", input, target) { hostProbes.incrementAndGet(); ok }
        }
        assertEquals(1, withoutPrompt.count("planned"))
        assertEquals(0, hostProbes.get())
        assertEquals(0, withoutPrompt.count("reported"))

        val hostBoundary = "测".repeat(4_000)
        System.setProperty("${withoutPrompt.property}.plan-prompt", "  $hostBoundary  ")
        val hostExecution = host.execute(withoutPrompt.manifest.id, "1.0.0", input, target) { hostProbes.incrementAndGet(); ok }
        assertEquals(hostBoundary, hostExecution.plan.prompt)
        assertEquals(1, hostProbes.get())
        assertEquals(1, withoutPrompt.count("reported"))
    }

    @Test
    fun `invalid plans and bounded plugin failures do not call the transport`(): Unit = runBlocking {
        val packaged = fixture(planTokens = 0)
        publish(packaged)
        val host = runtime()
        host.initialize(packaged.manifest.id)
        val probes = AtomicInteger()
        failure("PLUGIN_INVALID_PLAN") { host.execute(packaged.manifest.id, "1.0.0", input, target) { probes.incrementAndGet(); ok } }
        assertEquals(0, probes.get())
        assertEquals(0, packaged.count("reported"))
        val throwing = fixture(id = "throwing.probe", planPrefix = { "if (System.nanoTime() != 0) throw new IllegalStateException(\"PRIVATE_FAILURE_DETAIL\");" })
        publish(packaged, throwing)
        val second = runtime()
        second.initialize(throwing.manifest.id)
        val error = failure("PLUGIN_CODE_FAILED") { second.execute(throwing.manifest.id, "1.0.0", input, target) { probes.incrementAndGet(); ok } }
        assertFalse(error.message.orEmpty().contains("PRIVATE_FAILURE_DETAIL"))
        assertEquals(0, probes.get())
    }

    private class BoundProbeFailure(val domainCode: String) : RuntimeException(domainCode)

    @Test
    fun `ordinary bound callback failures retain identity and never produce a plugin report or retry`(): Unit = runBlocking {
        val packaged = fixture()
        publish(packaged)
        val host = runtime()
        host.initialize(packaged.manifest.id)
        val probes = AtomicInteger()
        val contexts = listOf(target, target.copy(kind = "oauth-account", id = "account-domain", credentialId = null))
        contexts.forEach { selected ->
            val domainFailure = BoundProbeFailure("credential-domain-${selected.kind}")
            val actual = assertFailsWith<BoundProbeFailure> {
                host.execute(packaged.manifest.id, "1.0.0", input, selected) { probes.incrementAndGet(); throw domainFailure }
            }
            assertSame(domainFailure, actual)
            assertEquals(0, packaged.count("reported"))
        }
        assertEquals(2, probes.get())
        assertEquals(2, packaged.count("planned"))
        assertBothProbeSlotsRecover(host)
    }

    @Test
    fun `callback cancellation propagates without a report and caller cancellation releases capacity`(): Unit = runBlocking {
        val packaged = fixture()
        publish(packaged)
        val host = runtime()
        host.initialize(packaged.manifest.id)
        val probes = AtomicInteger()
        assertFailsWith<CancellationException> {
            host.execute(packaged.manifest.id, "1.0.0", input, target) { probes.incrementAndGet(); throw CancellationException("bound callback cancelled") }
        }
        assertEquals(0, packaged.count("reported"))
        coroutineScope {
            val entered = CompletableDeferred<Unit>()
            val invocation = async(Dispatchers.Default) {
                host.execute(packaged.manifest.id, "1.0.0", input, target) {
                    probes.incrementAndGet()
                    entered.complete(Unit)
                    delay(10_000)
                    ok
                }
            }
            withTimeout(1_500) { entered.await() }
            invocation.cancel(CancellationException("caller cancelled"))
            withTimeout(1_500) { invocation.join() }
            assertTrue(invocation.isCancelled)
        }
        assertEquals(2, probes.get())
        assertEquals(0, packaged.count("reported"))
        assertBothProbeSlotsRecover(host)
    }

    @Test
    fun `plugin report exceptions remain sanitized after exactly one bound probe`(): Unit = runBlocking {
        val packaged = fixture(reportPrefix = "if (System.nanoTime() != 0) throw new IllegalStateException(\"PRIVATE_REPORT_DETAIL\");")
        publish(packaged)
        val host = runtime()
        host.initialize(packaged.manifest.id)
        val probes = AtomicInteger()
        val error = failure("PLUGIN_CODE_FAILED") {
            host.execute(packaged.manifest.id, "1.0.0", input, target) { probes.incrementAndGet(); ok }
        }
        assertTrue(error.message.orEmpty().contains("report failed (IllegalStateException)"))
        assertFalse(error.message.orEmpty().contains("PRIVATE_REPORT_DETAIL"))
        assertEquals(1, probes.get())
        assertEquals(1, packaged.count("reported"))
    }

    @Test
    fun `callback timeout is an outcome without retry and cancellation is respected`(): Unit = runBlocking {
        val packaged = fixture()
        publish(packaged)
        val host = runtime()
        host.initialize(packaged.manifest.id)
        val probes = AtomicInteger()
        val execution = host.execute(packaged.manifest.id, "1.0.0", input, target, ProbeLimits(timeoutMs = 150)) {
            probes.incrementAndGet()
            delay(1_000)
            ok
        }
        assertFalse(execution.outcome.success)
        assertTrue(execution.outcome.errorMessage.orEmpty().contains("timed out"))
        assertEquals(1, probes.get())
        assertEquals(1, packaged.count("reported"))
        assertBothProbeSlotsRecover(host)
    }

    @Test
    fun `OAuth callback gets separate preparation allowance while channel and HTTP plan budgets stay unchanged`(): Unit = runBlocking {
        assertEquals(45_000L, PluginExecutionBudgets.callbackTimeoutMs("channel", 45_000))
        assertEquals(65_000L, PluginExecutionBudgets.callbackTimeoutMs("oauth-account", 45_000))
        assertEquals(20_150L, PluginExecutionBudgets.callbackTimeoutMs("oauth-account", 150))
        failure { PluginExecutionBudgets.callbackTimeoutMs("unrecognized", 45_000) }
        val packaged = fixture()
        publish(packaged)
        val host = runtime()
        host.initialize(packaged.manifest.id)
        val account = target.copy(kind = "oauth-account", id = "account-1", credentialId = null)
        val native = host.execute(packaged.manifest.id, "1.0.0", input, account, ProbeLimits(timeoutMs = 150)) { plan ->
            assertEquals(150L, plan.timeoutMs)
            delay(300) // Simulate separately bounded native preparation before the HTTP helper.
            ok
        }
        assertTrue(native.outcome.success)
        assertEquals(10L, native.outcome.latencyMs) // Keep the adapter's upstream latency, not preparation.
        val channel = host.execute(packaged.manifest.id, "1.0.0", input, target, ProbeLimits(timeoutMs = 150)) {
            delay(300)
            ok
        }
        assertFalse(channel.outcome.success)
        assertTrue(channel.outcome.errorMessage.orEmpty().contains("150 ms"))
    }

    @Test
    fun `probe admission waiting is bounded separately and never reported as upstream latency`(): Unit = runBlocking {
        val packaged = fixture()
        publish(packaged)
        val host = runtime()
        host.initialize(packaged.manifest.id)
        val occupied = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val probes = AtomicInteger()
        coroutineScope {
            val running = List(2) { async(Dispatchers.Default) {
                host.execute(packaged.manifest.id, "1.0.0", input, target) {
                    if (probes.incrementAndGet() == 2) occupied.complete(Unit)
                    withTimeout(4_000) { release.await() }
                    ok
                }
            } }
            try {
                withTimeout(1_500) { occupied.await() }
                val error = failure("PLUGIN_BUSY") {
                    host.execute(packaged.manifest.id, "1.0.0", input, target, ProbeLimits(timeoutMs = 150)) { probes.incrementAndGet(); ok }
                }
                assertEquals(503, error.statusCode)
                assertEquals(2, probes.get())
            } finally {
                release.complete(Unit)
            }
            withTimeout(4_000) { running.forEach { assertEquals(10L, it.await().outcome.latencyMs) } }
        }
        assertBothProbeSlotsRecover(host)
    }

    @Test
    fun `blocking plan which ignores interrupts cannot defeat its host deadline`(): Unit = runBlocking {
        val release = CountDownLatch(1)
        val packaged = fixture(planPrefix = { property -> """
            java.util.concurrent.CountDownLatch release =
                (java.util.concurrent.CountDownLatch) System.getProperties().get("$property.plan-release");
            mark("plan-ready");
            while (true) {
                try { release.await(); break; } catch (InterruptedException ignored) { }
            }
            mark("plan-released");
        """.trimIndent() })
        System.getProperties().put("${packaged.property}.plan-release", release)
        publish(packaged)
        val host = runtime()
        host.initialize(packaged.manifest.id)
        val probes = AtomicInteger()
        withTimeout(10_000) {
            val caller = async(Dispatchers.Default) {
                failure("PLUGIN_CODE_TIMEOUT") {
                    host.execute(packaged.manifest.id, "1.0.0", input, target) { probes.incrementAndGet(); ok }
                }
            }
            try {
                awaitProperty("${packaged.property}.plan-ready", "1", timeoutMs = 8_000)
                caller.await()
                assertEquals(0, packaged.count("plan-released"), "The caller must time out while the plan is blocked")
                assertEquals(0, probes.get())
            } finally {
                release.countDown()
            }
            awaitProperty("${packaged.property}.plan-released", "1", timeoutMs = 5_000)
        }
    }

    @Test
    fun `transport concurrency is bounded to two independent workers`(): Unit = runBlocking {
        val packaged = fixture()
        publish(packaged)
        val host = runtime()
        host.initialize(packaged.manifest.id)
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val probes = AtomicInteger()
        coroutineScope {
            List(8) { async(Dispatchers.Default) {
                host.execute(packaged.manifest.id, "1.0.0", input, target) {
                    val count = inFlight.incrementAndGet()
                    peak.accumulateAndGet(count, ::maxOf)
                    probes.incrementAndGet()
                    try { delay(40); ok } finally { inFlight.decrementAndGet() }
                }
            } }.forEach { assertTrue(it.await().outcome.success) }
        }
        assertEquals(2, peak.get())
        assertEquals(8, probes.get())
    }

    @Test
    fun `code result ownership disposes late and delivered abandoned values exactly once`(): Unit = runBlocking {
        val disposed = AtomicInteger()
        val late = PluginRuntime.CodeResultOwnership<Any> { disposed.incrementAndGet() }
        late.discard()
        late.offer(Any())
        late.discard()
        assertNull(late.take())
        assertEquals(1, disposed.get())
        val delivered = PluginRuntime.CodeResultOwnership<Any> { disposed.incrementAndGet() }
        delivered.offer(Any())
        delivered.discard() // The continuation delivered its signal, but the outer timeout lost.
        delivered.discard()
        assertNull(delivered.take())
        assertEquals(2, disposed.get())
        val claimed = PluginRuntime.CodeResultOwnership<Any> { disposed.incrementAndGet() }
        val value = Any()
        claimed.offer(value)
        assertSame(value, claimed.take())
        claimed.discard()
        assertEquals(2, disposed.get())
    }

    @Test
    fun `timed out load disposes a delayed cooperative provider return even after runtime close`(): Unit = runBlocking {
        val packaged = ownedFixture(delayed = true)
        publish(packaged)
        val host = runtime()
        coroutineScope {
            val initialization = async(Dispatchers.Default) { host.initialize(packaged.manifest.id) }
            try {
                awaitProperty("${packaged.property}.load-ready", "1")
                assertNotNull(loader(packaged).findResource("fixture/owned-resource.txt"))
                withTimeout(4_000) { initialization.await() }
                awaitProperty("${packaged.property}.load-interrupted", "1")
                val state = host.state(packaged.manifest.id)
                assertEquals("error", state.status)
                assertTrue(state.loadError.orEmpty().contains("load exceeded its time bound"))
                assertNull(state.activeVersion)
                assertEquals(0, packaged.count("closed"))
            } finally {
                host.close()
                System.setProperty("${packaged.property}.release", "true")
            }
        }
        assertDisposed(packaged)
        assertNoActive(host)
        assertEquals(1, packaged.count("load-returned"))
    }

    @Test
    fun `cancelled initialization disposes a late cooperative load result without publication`(): Unit = runBlocking {
        val packaged = ownedFixture(delayed = true)
        publish(packaged)
        val host = runtime()
        coroutineScope {
            val initialization = async(Dispatchers.Default) { host.initialize(packaged.manifest.id) }
            try {
                awaitProperty("${packaged.property}.load-ready", "1")
                initialization.cancel(CancellationException("initialization cancelled"))
                withTimeout(1_500) { initialization.join() }
                assertTrue(initialization.isCancelled)
                awaitProperty("${packaged.property}.load-interrupted", "1")
                assertEquals(0, packaged.count("closed"))
                failure("PLUGIN_RUNTIME_NOT_INITIALIZED") { host.states() }
            } finally {
                host.close()
                System.setProperty("${packaged.property}.release", "true")
            }
        }
        assertDisposed(packaged)
        assertNoActive(host)
        assertEquals(1, packaged.count("load-returned"))
    }

    @Test
    fun `close racing startup publication cleans all pending providers and loaders without orphan active entries`(): Unit = runBlocking {
        val earlier = ownedFixture(id = "a.pending")
        val delayed = ownedFixture(id = "b.pending", delayed = true)
        val store = installedStore(earlier, delayed)
        val host = runtime(store)
        coroutineScope {
            val initialization = async(Dispatchers.Default) { runCatching { host.initialize() } }
            var closing: kotlinx.coroutines.Deferred<Unit>? = null
            try {
                awaitProperty("${delayed.property}.load-ready", "1")
                assertEquals(1, earlier.count("constructed"))
                assertNotNull(loader(earlier).findResource("fixture/owned-resource.txt"))
                val closeCall = async(Dispatchers.IO) { host.close() }
                closing = closeCall
                withTimeout(3_000) { closeCall.await() }
                awaitProperty("${delayed.property}.load-interrupted", "1")
                System.setProperty("${delayed.property}.release", "true")
                val error = assertNotNull(withTimeout(1_500) { initialization.await() }.exceptionOrNull() as? PluginHostException)
                assertEquals("PLUGIN_RUNTIME_CLOSED", error.code)
            } finally {
                // Release the constructor before waiting, even if close regresses and blocks.
                System.setProperty("${delayed.property}.release", "true")
                val closeCall = closing ?: async(Dispatchers.IO) { host.close() }
                withTimeout(3_000) { closeCall.await() }
            }
        }
        assertDisposed(earlier)
        assertDisposed(delayed)
        assertNoActive(host)
        host.close()
        assertEquals(1, earlier.count("closed"))
        assertEquals(1, delayed.count("closed"))
        failure("PLUGIN_RUNTIME_CLOSED") { host.contributions() }
    }

    @Test
    fun `cold load uses only package-local services and reports identity mismatch safely`(): Unit = runBlocking {
        val packaged = fixture()
        publish(packaged)
        val parentResources = root.resolve("parent")
        val service = parentResources.resolve(PluginPackages.SERVICE_PATH)
        Files.createDirectories(service.parent)
        Files.writeString(service, "nonexistent.ParentProvider\n")
        URLClassLoader(arrayOf(parentResources.toUri().toURL()), javaClass.classLoader).use { parent ->
            val host = runtime(parent = parent)
            host.initialize(packaged.manifest.id)
            assertEquals("1.0.0", host.state(packaged.manifest.id).activeVersion)
        }
        val mismatch = fixture(id = "mismatch.probe", providerId = "wrong.identity")
        publish(packaged, mismatch)
        val second = runtime()
        second.initialize(mismatch.manifest.id)
        val broken = second.state(mismatch.manifest.id)
        assertEquals("error", broken.status)
        assertNotNull(broken.loadError)
        assertNull(broken.activeUi)
        assertEquals(1, mismatch.count("closed"))
        assertFalse(broken.installed, "An unloadable bootstrap candidate must not leave an installation record")
        failure("PLUGIN_NOT_INSTALLED") { second.execute(mismatch.manifest.id, "1.0.0", input, target) { ok } }
    }

    @Test
    fun `staged packages survive catalogue removal but JAR tampering prevents startup admission`(): Unit = runBlocking {
        val packaged = fixture()
        publish(packaged)
        val store = MemoryInstallationStore()
        val host = runtime(store)
        host.initialize(packaged.manifest.id)
        host.close()
        Files.delete(catalogDirectory.resolve("catalog.json"))
        val independent = runtime(store)
        independent.initialize(packaged.manifest.id)
        assertTrue(independent.state(packaged.manifest.id).admissionEnabled)
        independent.close()
        val jar = managedDirectory.resolve(packaged.manifest.id).resolve("1.0.0/plugin.jar")
        try {
            Files.setPosixFilePermissions(jar, PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {
            // The tampering and fail-closed startup checks do not depend on POSIX permissions.
        }
        Files.write(jar, byteArrayOf(1, 2, 3))
        val tampered = runtime(store)
        tampered.initialize()
        val state = tampered.state(packaged.manifest.id)
        assertEquals("error", state.status)
        assertFalse(state.admissionEnabled)
        assertNull(state.activeVersion)
    }

    @Test
    fun `lost staging is recovered only from the recorded trusted pin without changing desired state`(): Unit = runBlocking {
        val packaged = fixture()
        publish(packaged)
        val store = MemoryInstallationStore()
        val original = runtime(store)
        original.initialize(packaged.manifest.id) { config("preserved across storage loss") }
        original.close()
        eraseManagedStorage()
        val writes = store.writes
        val restarted = runtime(store)
        restarted.initialize(packaged.manifest.id) { error("An existing installation must not reload legacy settings") }
        val state = restarted.state(packaged.manifest.id)
        assertTrue(state.admissionEnabled)
        assertEquals(config("preserved across storage loss"), state.activeConfiguration)
        assertEquals(writes, store.writes)
        assertEquals("preserved across storage loss", invoke(restarted).plan.prompt)
    }

    @Test
    fun `tombstone metadata and configuration recover from catalogue without staging or bootstrapping code`(): Unit = runBlocking {
        val packaged = fixture()
        publish(packaged)
        val store = MemoryInstallationStore()
        val original = runtime(store)
        original.initialize()
        original.install(packaged.manifest.id, "1.0.0", packaged.manifest.permissions)
        original.configure(packaged.manifest.id, config("retained tombstone config"))
        original.uninstall(packaged.manifest.id)
        original.close()
        eraseManagedStorage()
        val restarted = runtime(store)
        restarted.initialize(packaged.manifest.id) { error("A tombstone must not load legacy settings") }
        val tombstone = restarted.state(packaged.manifest.id)
        assertFalse(tombstone.installed)
        assertEquals(packaged.manifest.configuration, tombstone.configurationFields)
        assertEquals(config("retained tombstone config"), tombstone.configuration)
        assertNull(tombstone.activeVersion)
        assertFalse(Files.exists(managedDirectory))
        val changed = restarted.configure(packaged.manifest.id, config("edited retained config"))
        assertFalse(changed.installed || changed.desiredEnabled)
        assertFalse(Files.exists(managedDirectory))
        assertEquals(0, packaged.count("constructed"))
        val restored = restarted.install(packaged.manifest.id, "1.0.0", packaged.manifest.permissions)
        assertTrue(restored.installed)
        assertFalse(restored.desiredEnabled)
        assertEquals(config("edited retained config"), restored.configuration)
    }

    @Test
    fun `recorded digest prevents recovery or same-version replacement even when staging is gone`(): Unit = runBlocking {
        val packaged = fixture()
        publish(packaged)
        val store = MemoryInstallationStore()
        val original = runtime(store)
        original.initialize(packaged.manifest.id) { config("pinned config") }
        original.close()
        eraseManagedStorage()
        val replacement = fixture()
        publish(replacement)
        val restarted = runtime(store)
        restarted.initialize(packaged.manifest.id)
        val state = restarted.state(packaged.manifest.id)
        assertEquals("error", state.status)
        assertNull(state.activeVersion)
        assertFalse(Files.exists(managedDirectory))
        failure("PLUGIN_VERSION_CONFLICT") { restarted.install(packaged.manifest.id, "1.0.0", replacement.manifest.permissions) }
        assertEquals(packaged.sha256, store.find(packaged.manifest.id)?.sha256)
        assertEquals(config("pinned config"), state.configuration)
        assertEquals(0, replacement.count("constructed"))
    }

    @Test
    fun `plugin report copies reject growing collections after at most one excess entry`(): Unit = runBlocking {
        for ((collection, limit) in listOf("metrics" to 32, "notices" to 16)) {
            val type = if (collection == "metrics") "DiagnosticMetric" else "String"
            val entry = if (collection == "metrics") "new DiagnosticMetric(\"Metric\", \"Value\")" else "\"Notice\""
            val packaged = fixture(id = "growing.$collection", reportPrefix = """
                if (System.nanoTime() != 0) {
                    java.util.concurrent.atomic.AtomicInteger sizes = new java.util.concurrent.atomic.AtomicInteger();
                    java.util.concurrent.atomic.AtomicInteger entries = new java.util.concurrent.atomic.AtomicInteger();
                    java.util.List<$type> growing = (java.util.List<$type>) java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[] { java.util.List.class }, (proxy, method, arguments) -> {
                            if (method.getName().equals("size")) {
                                mark("collection-size");
                                return sizes.getAndIncrement() == 0 ? 1 : 1000000;
                            }
                            if (method.getName().equals("isEmpty")) return false;
                            if (method.getName().equals("iterator")) {
                                return java.util.stream.Stream.generate(() -> $entry).limit(1000000L).peek(value -> {
                                    mark("collection-next");
                                    if (entries.incrementAndGet() > ${limit + 1}) throw new IllegalStateException("unbounded report traversal");
                                }).iterator();
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
                    return new DiagnosticReport(true, "Growing report", ${if (collection == "metrics") "growing, Collections.emptyList()" else "Collections.emptyList(), growing"}, "OK");
                }
            """.trimIndent())
            publish(packaged)
            val host = runtime()
            host.initialize(packaged.manifest.id)
            try {
                val error = withTimeout(4_000) { failure("PLUGIN_INVALID_REPORT") { invoke(host, packaged.manifest.id) } }
                assertTrue(error.message.orEmpty().contains("declarative result limits"))
                assertTrue(packaged.count("collection-next") <= limit + 1, "Reject growing collections without unbounded traversal")
                assertEquals(1, packaged.count("reported"))
            } finally {
                host.close()
            }
        }
    }

    @Test
    fun `plugin report validates and returns the same collection snapshot`(): Unit = runBlocking {
        for (collection in listOf("metrics", "notices")) {
            val type = if (collection == "metrics") "DiagnosticMetric" else "String"
            val valid = if (collection == "metrics") "new DiagnosticMetric(\"Metric\", \"Value\")" else "\"Notice\""
            val invalid = if (collection == "metrics") "new DiagnosticMetric(\"x\".repeat(129), \"Value\")" else "\"x\".repeat(2001)"
            val packaged = fixture(id = "snapshot.$collection", reportPrefix = """
                if (System.nanoTime() != 0) {
                    java.util.concurrent.atomic.AtomicInteger traversals = new java.util.concurrent.atomic.AtomicInteger();
                    java.util.List<$type> changing = (java.util.List<$type>) java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[] { java.util.List.class }, (proxy, method, arguments) -> {
                            if (method.getName().equals("size")) return 1;
                            if (method.getName().equals("isEmpty")) return false;
                            if (method.getName().equals("get")) {
                                mark("collection-get");
                                return $invalid;
                            }
                            if (method.getName().equals("iterator")) {
                                mark("collection-iterator");
                                return Collections.singletonList(traversals.getAndIncrement() == 0 ? $valid : $invalid).iterator();
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
                    return new DiagnosticReport(true, "Changing report", ${if (collection == "metrics") "changing, Collections.emptyList()" else "Collections.emptyList(), changing"}, "OK");
                }
            """.trimIndent())
            publish(packaged)
            val host = runtime()
            host.initialize(packaged.manifest.id)
            try {
                val report = withTimeout(4_000) { invoke(host, packaged.manifest.id).report }
                if (collection == "metrics") {
                    assertEquals("Metric", report.metrics.single().label)
                    assertEquals("Value", report.metrics.single().value)
                } else {
                    assertEquals(listOf("Notice"), report.notices)
                }
                assertEquals(1, packaged.count("collection-iterator"))
                assertEquals(0, packaged.count("collection-get"))
            } finally {
                host.close()
            }
        }
    }

    @Test
    fun `plugin report collection access remains inside the timed worker`(): Unit = runBlocking {
        for (collection in listOf("metrics", "notices")) {
            val ready = CountDownLatch(1)
            val release = CountDownLatch(1)
            val type = if (collection == "metrics") "DiagnosticMetric" else "String"
            val packaged = fixture(id = "blocking.$collection", reportPrefix = """
                if (System.nanoTime() != 0) {
                    java.util.List<$type> lazy = (java.util.List<$type>) java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[] { java.util.List.class }, (proxy, method, arguments) -> {
                            if (method.getName().equals("size")) return 1;
                            if (method.getName().equals("iterator")) {
                                java.util.concurrent.CountDownLatch release = (java.util.concurrent.CountDownLatch)
                                    System.getProperties().get(getClass().getName() + ".collection-release");
                                ((java.util.concurrent.CountDownLatch) System.getProperties().get(getClass().getName() + ".collection-ready")).countDown();
                                boolean released = false;
                                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                                while (!released && System.nanoTime() < deadline) {
                                    try { released = release.await(10, java.util.concurrent.TimeUnit.MILLISECONDS); }
                                    catch (InterruptedException ignored) { }
                                }
                                if (!released) throw new IllegalStateException("test collection was not released");
                                mark("collection-released");
                                return Collections.<$type>emptyList().iterator();
                            }
                            if (method.getName().equals("isEmpty")) return true;
                            throw new UnsupportedOperationException(method.getName());
                        });
                    return new DiagnosticReport(true, "Lazy report", ${if (collection == "metrics") "lazy, Collections.emptyList()" else "Collections.emptyList(), lazy"}, "OK");
                }
            """.trimIndent())
            System.getProperties()["${packaged.providerClass}.collection-ready"] = ready
            System.getProperties()["${packaged.providerClass}.collection-release"] = release
            publish(packaged)
            val host = runtime()
            host.initialize(packaged.manifest.id)
            try {
                coroutineScope {
                    val invocation = async(Dispatchers.Default) {
                        runCatching { host.execute(packaged.manifest.id, "1.0.0", input, target, ProbeLimits(timeoutMs = 1_500)) { ok } }
                    }
                    try {
                        assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS), "The report collection iteration must be entered before checking its timeout")
                        val error = assertNotNull(withTimeout(4_000) { invocation.await() }.exceptionOrNull() as? PluginHostException)
                        assertEquals("PLUGIN_CODE_TIMEOUT", error.code)
                        assertTrue(error.message.orEmpty().contains("report exceeded its time bound"))
                        assertEquals(0, packaged.count("collection-released"), "The caller must time out while collection iteration is blocked")
                    } finally {
                        release.countDown()
                    }
                }
                awaitProperty("${packaged.property}.collection-released", "1")
            } finally {
                release.countDown()
                host.close()
                System.getProperties().remove("${packaged.providerClass}.collection-ready")
                System.getProperties().remove("${packaged.providerClass}.collection-release")
            }
        }
    }

    @Test
    fun `all plugin code runs with its package loader as thread context loader`(): Unit = runBlocking {
        val check = "if (Thread.currentThread().getContextClassLoader() != getClass().getClassLoader()) throw new IllegalStateException(\"wrong context loader\");"
        val packaged = fixture(constructorPrefix = { check }, planPrefix = { check }, reportPrefix = check)
        publish(packaged)
        val host = runtime()
        host.initialize(packaged.manifest.id)
        assertTrue(invoke(host).report.success)
    }

    @Test
    fun `queued provider cleanup survives host close deadline and retains loaders until close returns`(): Unit = runBlocking {
        val packages = List(3) { ownedFixture(id = "cleanup.provider$it", delayedClose = true) }
        val store = installedStore(*packages.toTypedArray())
        val host = runtime(store)
        host.initialize()
        packages.forEach { assertNotNull(loader(it).findResource("fixture/owned-resource.txt")) }
        val closing = async(Dispatchers.IO) { host.close() }
        try {
            withTimeout(3_000) { closing.await() } // Caller is bounded even if disposal workers block.
            withTimeout(1_500) {
                while (packages.sumOf { it.count("close-ready") } < 2) delay(5)
            }
            assertEquals(2, packages.sumOf { it.count("closed") })
            packages.forEach {
                assertEquals(0, it.count("close-returned"))
                assertNotNull(loader(it).findResource("fixture/owned-resource.txt"))
            }
        } finally {
            packages.forEach { System.setProperty("${it.property}.close-release", "true") }
            withTimeout(3_000) { closing.await() }
        }
        packages.forEach {
            awaitProperty("${it.property}.close-returned", "1")
            assertDisposed(it)
        }
        assertNoActive(host)
    }

    @Test
    fun `timed out workers retain their loader after provider close until actual exit`(): Unit = runBlocking {
        for (operation in listOf("plan", "report")) {
            val ready = CountDownLatch(1)
            val release = CountDownLatch(1)
            val blockingWork = """
                ((java.util.concurrent.CountDownLatch) System.getProperties().get(getClass().getName() + ".ready")).countDown();
                boolean released = false;
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                while (!released && System.nanoTime() < deadline) {
                    try { released = ((java.util.concurrent.CountDownLatch) System.getProperties().get(getClass().getName() + ".release")).await(10, java.util.concurrent.TimeUnit.MILLISECONDS); }
                    catch (InterruptedException ignored) { }
                }
                if (!released) throw new IllegalStateException("test work was not released");
                if (getClass().getClassLoader().getResource("fixture/owned-resource.txt") == null) throw new IllegalStateException("loader closed during work");
                mark("resource-after-close");
            """.trimIndent()
            val packaged = fixture(
                id = "retained.$operation",
                constructorPrefix = { property -> "System.getProperties().put(\"$property.loader\", getClass().getClassLoader());" },
                planPrefix = { if (operation == "plan") blockingWork else "" },
                reportPrefix = if (operation == "report") blockingWork else "",
                closePrefix = { "mark(\"closed-with-loader\");" },
                jarEntries = listOf(ZipFixtureEntry("fixture/owned-resource.txt", "owned".toByteArray())),
            )
            System.getProperties()["${packaged.providerClass}.ready"] = ready
            System.getProperties()["${packaged.providerClass}.release"] = release
            publish(packaged)
            val host = runtime()
            host.initialize(packaged.manifest.id)
            coroutineScope {
                val invocation = async(Dispatchers.Default) {
                    runCatching {
                        host.execute(packaged.manifest.id, "1.0.0", input, target, ProbeLimits(timeoutMs = 500)) { ok }
                    }
                }
                try {
                    assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    val error = withTimeout(3_000) { invocation.await() }.exceptionOrNull() as PluginHostException
                    assertEquals("PLUGIN_CODE_TIMEOUT", error.code)
                    host.close()
                    // SDK contract: provider close may stop/unblock active calls; only loader
                    // release waits for the actual worker to exit as well as provider close.
                    awaitProperty("${packaged.property}.closed", "1")
                    assertNotNull(loader(packaged).findResource("fixture/owned-resource.txt"))
                    assertEquals(0, packaged.count("resource-after-close"))
                } finally {
                    release.countDown()
                    host.close()
                }
            }
            awaitProperty("${packaged.property}.resource-after-close", "1")
            assertDisposed(packaged)
            System.getProperties().remove("${packaged.providerClass}.ready")
            System.getProperties().remove("${packaged.providerClass}.release")
        }
    }

    @Test
    fun `close is idempotent closes providers and denies later calls`(): Unit = runBlocking {
        val packaged = ownedFixture()
        publish(packaged)
        val host = runtime()
        host.initialize(packaged.manifest.id)
        assertNotNull(loader(packaged).findResource("fixture/owned-resource.txt"))
        host.close()
        host.close()
        assertDisposed(packaged)
        assertNoActive(host)
        failure("PLUGIN_RUNTIME_CLOSED") { invoke(host) }
        failure("PLUGIN_RUNTIME_CLOSED") { host.states() }
    }
}
