package dev.soven.prismllm.plugin.host

import dev.soven.prismllm.plugin.sdk.DiagnosticContext
import dev.soven.prismllm.plugin.sdk.DiagnosticInput
import dev.soven.prismllm.plugin.sdk.DiagnosticPlugin
import dev.soven.prismllm.plugin.sdk.DiagnosticReport
import dev.soven.prismllm.plugin.sdk.DiagnosticTarget
import dev.soven.prismllm.plugin.sdk.PluginApi
import dev.soven.prismllm.plugin.sdk.ProbeLimits
import dev.soven.prismllm.plugin.sdk.ProbeOutcome
import dev.soven.prismllm.plugin.sdk.ProbePlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.Enumeration
import java.util.ServiceLoader
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resumeWithException

/**
 * Desired state lives in the CAS store. Loaded code, grants, admission and configuration snapshots
 * belong to this process. A closed admission latch can only be reopened by a new runtime at startup.
 * Trusted JVM code is bounded, not sandboxed; it never receives provider credentials or a transport.
 */
class PluginRuntime(
    private val catalog: PluginCatalog,
    managedDirectory: Path,
    private val store: PluginInstallationStore,
    private val parentClassLoader: ClassLoader = DiagnosticPlugin::class.java.classLoader,
) : AutoCloseable {
    private val installer = PluginPackageInstaller(managedDirectory)
    private val mutex = Mutex()
    private val lifecycleLock = Any()
    private val closed = AtomicBoolean(false)
    private var initialized = false
    private val active = ConcurrentHashMap<String, LoadedPlugin>()
    private val metadata = HashMap<Selection, StagedPluginPackage>()
    private val loadErrors = HashMap<Selection, String>()
    private val bootstrapErrors = HashMap<String, String>()
    private val probeWorkers = Semaphore(2)
    private val executions = Semaphore(32)
    private val workerSequence = AtomicInteger()
    private val codeWorkers = ThreadPoolExecutor(
        2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32),
        ThreadFactory { task -> Thread(task, "prism-plugin-code-${workerSequence.incrementAndGet()}").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    // Only owned resources enqueue cleanup, never invocations. Late cooperative load returns must
    // still be disposed after close(); these two daemon workers expire when idle instead of rejecting
    // those late owners. Caller waiting is bounded, not a promise to stop uncooperative JVM code.
    private val cleanupWorkers = ThreadPoolExecutor(
        2, 2, 1, TimeUnit.SECONDS, LinkedBlockingQueue(),
        ThreadFactory { task -> Thread(task, "prism-plugin-close-${workerSequence.incrementAndGet()}").apply { isDaemon = true } },
    ).apply { allowCoreThreadTimeOut(true) }

    init {
        PluginValidation.requireData(!installer.directory.startsWith(catalog.directory) && !catalog.directory.startsWith(installer.directory), "Catalogue and managed plugin directories must be separate")
    }

    suspend fun initialize(
        bootstrapId: String? = null,
        initialConfiguration: suspend () -> JsonObject = { JsonObject(emptyMap()) },
    ) {
        mutex.withLock {
            checkOpen()
            if (initialized) return
            val pending = LinkedHashMap<String, LoadedPlugin>()
            var published = false
            try {
                if (bootstrapId != null && store.find(bootstrapId) == null) {
                    try {
                        val candidates = withContext(Dispatchers.IO) { catalog.bootstrapCandidates(bootstrapId) }
                        var firstCandidateFailure: PluginHostException? = null
                        var resolved = candidates.isEmpty()
                        for (reference in candidates) {
                            // Another writer (including an uninstall tombstone) wins without being
                            // overwritten. The ordinary managed-install pass below handles that row.
                            if (store.find(bootstrapId) != null) {
                                resolved = true
                                break
                            }
                            checkOpen()
                            var staged: StagedPluginPackage? = null
                            var loaded: LoadedPlugin? = null
                            try {
                                val candidate = withContext(Dispatchers.IO) { catalog.bootstrapCandidate(reference) }
                                staged = withContext(Dispatchers.IO) { installer.stage(candidate) }
                                val defaultConfiguration = PluginValidation.installationConfiguration(candidate.item.manifest.configuration, null)
                                loaded = load(
                                    staged,
                                    PluginValidation.grants(candidate.item.manifest, candidate.item.manifest.permissions).toSet(),
                                    defaultConfiguration,
                                )
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: PluginHostException) {
                                if (error.code == "PLUGIN_RUNTIME_CLOSED") throw error
                                if (firstCandidateFailure == null) firstCandidateFailure = catalog.bootstrapFailure(error)
                                continue
                            } catch (error: Exception) {
                                if (firstCandidateFailure == null) firstCandidateFailure = PluginHostException(
                                    "PLUGIN_INCOMPATIBLE", "PLUGIN_BOOTSTRAP_FAILED: Optional plugin candidate failed (${error.javaClass.simpleName})", 503,
                                )
                                continue
                            }

                            try {
                                val acceptedPackage = checkNotNull(staged)
                                var createdInstallation: PluginInstallation? = null
                                val installation = store.getOrCreate(bootstrapId) {
                                    val values = try {
                                        initialConfiguration()
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (_: Exception) {
                                        throw PluginHostException("PLUGIN_BOOTSTRAP_CONFIGURATION_UNAVAILABLE", "Initial plugin configuration could not be read", 503)
                                    }
                                    val fields = acceptedPackage.item.manifest.configuration
                                    val configuration = try {
                                        val retained = PluginValidation.installationConfiguration(fields, values)
                                        PluginValidation.configuration(fields, retained)
                                    } catch (error: PluginHostException) {
                                        throw BootstrapCandidateConfigurationFailure(error)
                                    }
                                    checkOpen()
                                    val proposed = PluginInstallation(
                                        id = bootstrapId, version = acceptedPackage.item.manifest.version, sha256 = acceptedPackage.item.sha256,
                                        source = acceptedPackage.item.source, installed = true, desiredEnabled = true,
                                        approvedPermissions = PluginValidation.grants(acceptedPackage.item.manifest, acceptedPackage.item.manifest.permissions),
                                        configuration = configuration, updatedAt = System.currentTimeMillis(), revision = 0,
                                    )
                                    createdInstallation = proposed
                                    proposed
                                }
                                val wonInsert = createdInstallation === installation
                                val matchesCandidate = installation.version == acceptedPackage.item.manifest.version &&
                                    installation.sha256.equals(acceptedPackage.item.sha256, ignoreCase = true)
                                if (wonInsert && matchesCandidate) {
                                    metadata[Selection(installation)] = acceptedPackage
                                    if (installation.installed && installation.desiredEnabled) {
                                        val accepted = checkNotNull(loaded)
                                        accepted.configuration = installation.configuration
                                        pending[installation.id] = accepted
                                        loaded = null
                                    }
                                }
                                bootstrapErrors.remove(bootstrapId)
                                resolved = true
                                break
                            } catch (error: BootstrapCandidateConfigurationFailure) {
                                if (firstCandidateFailure == null) firstCandidateFailure = catalog.bootstrapFailure(
                                    PluginHostException("PLUGIN_INVALID_CONFIGURATION", error.validation.message ?: "Invalid plugin configuration"),
                                )
                            } finally {
                                loaded?.let(::dispose)
                            }
                        }
                        if (!resolved) throw firstCandidateFailure ?: PluginHostException(
                            "PLUGIN_BOOTSTRAP_FAILED", "No bundled plugin candidate could be loaded", 503,
                        )
                    } catch (error: PluginHostException) {
                        if (error.code == "PLUGIN_RUNTIME_CLOSED") throw error
                        // Optional deployment/staging failures must not skip healthy managed installs.
                        bootstrapErrors[bootstrapId] = "${error.code}: ${error.message}".take(4_000)
                    }
                }
                for (installation in store.list().sortedBy { it.id }) {
                    checkOpen()
                    val enabled = installation.installed && installation.desiredEnabled
                    val previous = pending[installation.id]
                    if (enabled && previous != null && sameSelection(installation, previous) && installation.disableGeneration == previous.disableGeneration) continue
                    // The listed decision supersedes an unpublished owner, even if it disables loading.
                    pending.remove(installation.id)?.let(::dispose)
                    if (!enabled) continue
                    val selection = Selection(installation)
                    try {
                        val staged = packageFor(installation)
                        val grants = PluginValidation.grants(staged.item.manifest, installation.approvedPermissions).toSet()
                        val configuration = configuration(staged, installation.configuration)
                        pending[installation.id] = load(staged, grants, configuration, installation.disableGeneration)
                    } catch (error: PluginHostException) {
                        loadErrors[selection] = error.message ?: "Plugin could not be loaded"
                    }
                }
                currentCoroutineContext().ensureActive()
                synchronized(lifecycleLock) {
                    checkOpen()
                    active.putAll(pending)
                    initialized = true
                    published = true
                }
            } finally {
                // Until atomic publication, initialization owns every returned provider/loader.
                if (!published) pending.values.forEach(::dispose)
            }
        }
    }

    fun catalogItems(): List<PluginCatalogItem> {
        checkOpen()
        return catalog.items()
    }

    suspend fun states(): List<PluginRuntimeState> = mutex.withLock {
        checkReady()
        val installations = store.list().sortedBy { it.id }
        closeMissing(installations)
        val installedIds = installations.mapTo(HashSet()) { it.id }
        (installations.map { render(it) } + bootstrapErrors.filterKeys { it !in installedIds }.map { (id, error) -> bootstrapState(id, error) }).sortedBy { it.id }
    }

    suspend fun state(id: String): PluginRuntimeState = mutex.withLock {
        checkReady()
        store.find(id)?.let { render(it) } ?: run {
            retireMissing(id)
            bootstrapErrors[id]?.let { bootstrapState(id, it) }
                ?: throw PluginHostException("PLUGIN_NOT_INSTALLED", "No plugin installation or tombstone exists for this id", 404)
        }
    }

    /** Recheck live admission and the grants belonging to the executable version, not a pending install. */
    suspend fun requireActivePermission(id: String, expectedActiveVersion: String, permission: String) {
        mutex.withLock {
            checkReady()
            val installation = find(id)
            observe(installation)
            val loaded = active[id] ?: throw PluginHostException("PLUGIN_NOT_ACTIVE", "Plugin is not loaded; enable it and restart the application", 409)
            requireAdmission(installation, loaded, expectedActiveVersion)
            if (permission !in loaded.grants || permission !in loaded.staged.item.manifest.permissions) {
                throw PluginHostException("PLUGIN_PERMISSION_REQUIRED", "Plugin is not approved for this diagnostic context", 403)
            }
        }
    }

    suspend fun contributions(): List<PluginUiContribution> = mutex.withLock {
        checkReady()
        val installations = store.list().sortedBy { it.id }
        closeMissing(installations)
        installations.flatMap { installation ->
            observe(installation)
            val loaded = active[installation.id]
            if (loaded == null || !admitted(installation, loaded)) emptyList()
            else loaded.staged.item.ui.entries.map { PluginUiContribution(installation.id, loaded.staged.item.manifest.version, it) }
        }
    }

    /** Initial values seed only a new record; a lost insert renders the concurrent winner unchanged. */
    suspend fun install(
        id: String,
        version: String,
        approvedPermissions: List<String>,
        initialConfiguration: suspend () -> JsonObject = { JsonObject(emptyMap()) },
    ): PluginRuntimeState = installUsing(id, version, approvedPermissions, null, initialConfiguration)

    /** User approval is bound to the verified catalogue package displayed by the client. */
    suspend fun install(
        id: String,
        version: String,
        approvedPermissions: List<String>,
        expectedSha256: String,
        initialConfiguration: suspend () -> JsonObject = { JsonObject(emptyMap()) },
    ): PluginRuntimeState = installUsing(id, version, approvedPermissions, expectedSha256, initialConfiguration)

    private suspend fun installUsing(
        id: String,
        version: String,
        approvedPermissions: List<String>,
        expectedSha256: String?,
        initialConfiguration: suspend () -> JsonObject,
    ): PluginRuntimeState = mutex.withLock {
        checkReady()
        val validated = withContext(Dispatchers.IO) { catalog.select(id, version) }
        if (expectedSha256 != null) {
            PluginValidation.digest(expectedSha256)
            if (!validated.item.sha256.equals(expectedSha256, ignoreCase = true)) {
                throw PluginHostException("PLUGIN_CATALOG_CONFLICT", "The approved catalogue package changed; refresh and review its digest and permissions", 409)
            }
        }
        val grants = PluginValidation.grants(validated.item.manifest, approvedPermissions)
        val previous = store.find(id)
        if (previous != null && previous.version == version && !previous.sha256.equals(validated.item.sha256, ignoreCase = true)) {
            throw PluginHostException("PLUGIN_VERSION_CONFLICT", "A recorded immutable plugin version cannot be replaced with a different digest", 409)
        }
        val staged = withContext(Dispatchers.IO) { installer.stage(validated) }
        metadata[Selection(id, version, staged.item.sha256)] = staged
        if (previous != null && previous.installed && previous.version == version && previous.sha256.equals(staged.item.sha256, ignoreCase = true)) {
            // Do not erase configuration, toggle enablement or increment the revision on replay.
            return@withLock render(previous)
        }
        fun installation(values: JsonObject) = PluginInstallation(
            id = id, version = version, sha256 = staged.item.sha256, source = staged.item.source,
            installed = true, desiredEnabled = previous?.let { it.installed && it.desiredEnabled } ?: false,
            approvedPermissions = grants,
            configuration = PluginValidation.installationConfiguration(staged.item.manifest.configuration, values),
            updatedAt = now(previous), revision = nextRevision(previous),
            disableGeneration = previous?.disableGeneration ?: 0,
        )
        val next = if (previous == null) {
            store.getOrCreate(id) {
                val values = try {
                    initialConfiguration()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    throw PluginHostException("PLUGIN_INITIAL_CONFIGURATION_UNAVAILABLE", "Initial plugin configuration could not be read", 503)
                }
                checkOpen()
                installation(values)
            }
        } else {
            installation(previous.configuration).also { persist(it, previous) }
        }
        bootstrapErrors.remove(id)
        render(next)
    }

    /** Enable only the installation selection the administrator reviewed. */
    suspend fun enable(
        id: String,
        expectedRevision: Long,
        installedVersion: String,
        approvedPermissions: List<String>? = null,
    ): PluginRuntimeState = changeEnabled(id, true, approvedPermissions) { previous ->
        validateExpectedRevision(expectedRevision)
        if (previous.revision != expectedRevision || previous.version != installedVersion) {
            throw PluginHostException("PLUGIN_REVISION_CONFLICT", "Plugin installation changed; refresh and confirm the version and permissions again", 409)
        }
    }

    suspend fun disable(id: String): PluginRuntimeState = stoppingAdmission(id) {
        changeEnabled(id, false, null) {}
    }

    private suspend fun stoppingAdmission(id: String, change: suspend () -> PluginRuntimeState): PluginRuntimeState {
        // Block before waiting for the mutex/database, but only committed stops close the latch.
        val loaded = active[id]
        loaded?.pendingStops?.incrementAndGet()
        var committed = false
        try {
            return change().also { committed = true }
        } finally {
            if (loaded != null) {
                try {
                    if (committed) loaded.admission.set(false)
                    else withContext(NonCancellable) {
                        try {
                            val checked = withTimeoutOrNull(CODE_TIMEOUT_MS) {
                                mutex.withLock {
                                    val current = store.find(id)
                                    if (current == null) loaded.admission.set(false) else observe(current)
                                }
                                true
                            }
                            if (checked != true) loaded.admission.set(false)
                        } catch (_: Exception) {
                            // Unknown persistence state cannot safely restore admission.
                            loaded.admission.set(false)
                        }
                    }
                } finally {
                    loaded.pendingStops.decrementAndGet()
                }
            }
        }
    }

    private suspend fun changeEnabled(
        id: String,
        enabled: Boolean,
        approvedPermissions: List<String>?,
        validateSelection: (PluginInstallation) -> Unit,
    ): PluginRuntimeState = mutex.withLock {
        checkReady()
        val previous = find(id)
        validateSelection(previous)
        if (!previous.installed) throw PluginHostException("PLUGIN_NOT_INSTALLED", "Restore the plugin installation before changing enablement", 409)
        val grants = if (enabled) {
            val staged = packageFor(previous)
            configuration(staged, previous.configuration)
            PluginValidation.grants(staged.item.manifest, approvedPermissions ?: previous.approvedPermissions)
        } else previous.approvedPermissions
        if (previous.desiredEnabled == enabled && previous.approvedPermissions == grants) return@withLock render(previous)
        val next = previous.copy(
            desiredEnabled = enabled, approvedPermissions = grants.toList(), updatedAt = now(previous), revision = nextRevision(previous),
            disableGeneration = if (enabled) previous.disableGeneration else nextDisableGeneration(previous),
        )
        persist(next, previous)
        // Enabling never changes the process's admission latch, even when this class is loaded.
        render(next)
    }

    suspend fun configure(id: String, values: JsonObject): PluginRuntimeState = configureUsing(id) { values }

    /** Compare the client's original draft baseline before validation, including no-op writes. */
    suspend fun configure(id: String, values: JsonObject, expectedRevision: Long, installedVersion: String): PluginRuntimeState =
        configureUsing(id, expectedRevision, installedVersion) { values }

    /** Merge against the configuration read with the revision used by the subsequent CAS. */
    suspend fun configureFields(id: String, values: JsonObject): PluginRuntimeState = configureUsing(id) {
        JsonObject(it + values)
    }

    private suspend fun configureUsing(
        id: String,
        expectedRevision: Long? = null,
        installedVersion: String? = null,
        values: (JsonObject) -> JsonObject,
    ): PluginRuntimeState = mutex.withLock {
        checkReady()
        val previous = find(id)
        expectedRevision?.let(::validateExpectedRevision)
        if ((expectedRevision != null && previous.revision != expectedRevision) ||
            (installedVersion != null && previous.version != installedVersion)) {
            throw PluginHostException("PLUGIN_REVISION_CONFLICT", "Plugin configuration changed since this draft was loaded; refresh and discard the stale draft", 409)
        }
        val item = try {
            packageFor(previous).item
        } catch (error: PluginHostException) {
            if (previous.installed) throw error
            // Metadata-only recovery allows editing retained tombstone config, not installation.
            withContext(Dispatchers.IO) { pinnedPackage(previous).item }
        }
        val configuration = configuration(item, values(PluginValidation.snapshot(previous.configuration)))
        if (configuration == previous.configuration) return@withLock render(previous)
        val next = previous.copy(configuration = configuration, updatedAt = now(previous), revision = nextRevision(previous))
        persist(next, previous)
        val loaded = active[id]
        if (loaded != null && sameSelection(next, loaded)) loaded.configuration = PluginValidation.snapshot(configuration)
        // A pending version uses its own fields/config; old executable metadata remains untouched.
        render(next)
    }

    suspend fun uninstall(id: String): PluginRuntimeState = stoppingAdmission(id) {
        mutex.withLock {
            checkReady()
            val previous = find(id)
            if (!previous.installed && !previous.desiredEnabled) return@withLock render(previous)
            val next = previous.copy(
                installed = false, desiredEnabled = false, updatedAt = now(previous), revision = nextRevision(previous),
                disableGeneration = nextDisableGeneration(previous),
            )
            persist(next, previous)
            // No deletion: both restoration data and bootstrap suppression survive a restart.
            render(next)
        }
    }

    suspend fun execute(
        id: String,
        expectedActiveVersion: String,
        input: DiagnosticInput,
        target: DiagnosticTarget,
        limits: ProbeLimits = ProbeLimits(),
        probe: suspend (ProbePlan) -> ProbeOutcome,
    ): PluginExecution {
        checkOpen()
        if (active[id]?.admissionOpen() == false) throw PluginHostException("PLUGIN_DISABLED", "Plugin admission is disabled", 409)
        if (!executions.tryAcquire()) throw PluginHostException("PLUGIN_BUSY", "Plugin invocation capacity is full", 503)
        try {
            val boundedLimits = limits(limits)
            val snapshot = withTimeoutOrNull(minOf(CODE_TIMEOUT_MS, boundedLimits.timeoutMs)) { mutex.withLock {
                checkReady()
                val installation = find(id)
                observe(installation)
                val loaded = active[id] ?: throw PluginHostException("PLUGIN_NOT_ACTIVE", "Plugin is not loaded; enable it and restart the application", 409)
                requireAdmission(installation, loaded, expectedActiveVersion)
                PluginValidation.target(target)
                val permission = PluginValidation.permission(target.kind)
                if (permission !in loaded.grants || permission !in loaded.staged.item.manifest.permissions) {
                    throw PluginHostException("PLUGIN_PERMISSION_REQUIRED", "Plugin is not approved for this diagnostic context", 403)
                }
                val action = loaded.staged.item.ui.entries.firstOrNull { it.action.id == input.actionId && target.kind in it.contexts }?.action
                    ?: throw PluginHostException("PLUGIN_ACTION_NOT_AVAILABLE", "Action is not available for this active plugin/context", 400)
                val actionLimits = boundedLimits.copy(
                    maxPromptChars = minOf(
                        boundedLimits.maxPromptChars,
                        action.fields.firstOrNull { it.key == "prompt" }?.maxLength ?: boundedLimits.maxPromptChars,
                    ),
                )
                val configuration = PluginValidation.snapshot(loaded.configuration)
                val values = try {
                    PluginValidation.input(action, input.values, target, configuration, actionLimits.maxPromptChars)
                } catch (error: PluginHostException) {
                    throw PluginHostException("PLUGIN_INVALID_INPUT", error.message ?: "Invalid invocation values")
                }
                ExecutionSnapshot(loaded, DiagnosticInput(input.actionId, values), DiagnosticContext(target.copy(), configuration, actionLimits))
            } } ?: throw PluginHostException("PLUGIN_BUSY", "Timed out admitting diagnostic execution", 503)
            val rawPlan = runCode("plan", minOf(CODE_TIMEOUT_MS, boundedLimits.timeoutMs), snapshot.loaded.loader, owner = snapshot.loaded) { snapshot.loaded.plugin.plan(snapshot.input, snapshot.context) }
            val plan = plan(rawPlan, snapshot.context.limits)
            val boundedOutcome = withProbePermit(plan.timeoutMs) {
                val checked = withTimeoutOrNull(minOf(CODE_TIMEOUT_MS, plan.timeoutMs)) {
                    mutex.withLock {
                        checkReady()
                        val current = find(id)
                        requireAdmission(current, snapshot.loaded, expectedActiveVersion)
                        if (active[id] !== snapshot.loaded) throw PluginHostException("PLUGIN_NOT_ACTIVE", "Active plugin changed", 409)
                        if (PluginValidation.permission(target.kind) !in snapshot.loaded.grants) throw PluginHostException("PLUGIN_PERMISSION_REQUIRED", "Plugin probe permission was revoked", 403)
                    }
                    true
                }
                if (checked != true) throw PluginHostException("PLUGIN_BUSY", "Timed out revalidating diagnostic admission", 503)
                if (closed.get() || !snapshot.loaded.admissionOpen()) throw PluginHostException("PLUGIN_DISABLED", "Plugin admission is disabled", 409)
                // Admission waiting is NOT upstream latency. The callback binds the authorized target
                // and prepares secrets only after this final gate. OAuth gets a separate 20s envelope
                // allowance for native preparation; its HTTP helper still receives plan.timeoutMs.
                val started = System.nanoTime()
                val callbackTimeoutMs = PluginExecutionBudgets.callbackTimeoutMs(target.kind, plan.timeoutMs)
                // Transport helpers return intentional upstream/network outcomes. Bound callback
                // failures belong to the caller's domain and must propagate without a plugin report.
                val result = withTimeoutOrNull(callbackTimeoutMs) { probe(plan) }
                    ?: ProbeOutcome(false, null, elapsed(started), errorMessage = "Probe callback timed out after $callbackTimeoutMs ms")
                // Coroutine cancellation is best-effort, not a promise to abort already-started HTTP.
                outcome(result)
            }
            val report = runCode("report", minOf(CODE_TIMEOUT_MS, boundedLimits.timeoutMs), snapshot.loaded.loader, owner = snapshot.loaded) {
                // Accessing plugin-supplied collections can execute plugin code too.
                report(snapshot.loaded.plugin.report(plan, boundedOutcome))
            }
            return PluginExecution(id, snapshot.loaded.staged.item.manifest.version, snapshot.context.target, plan, boundedOutcome, report)
        } finally {
            executions.release()
        }
    }

    private suspend fun load(staged: StagedPluginPackage, grants: Set<String>, configuration: JsonObject, disableGeneration: Long = 0): LoadedPlugin {
        val configurationSnapshot = PluginValidation.snapshot(configuration)
        val loader = PackageClassLoader(staged.jarPath.toUri().toURL(), parentClassLoader)
        val loaderClaimed = AtomicBoolean(false)
        try {
            return runCode("load", CODE_TIMEOUT_MS, loader, onDiscard = { dispose(it) }) {
                // If timeout wins before the task starts, the caller claims and closes the unused loader.
                if (!loaderClaimed.compareAndSet(false, true)) throw PluginHostException("PLUGIN_CODE_TIMEOUT", "Plugin load no longer has an owner", 503)
                try {
                    val providers = ServiceLoader.load(DiagnosticPlugin::class.java, loader).stream().toList()
                    if (providers.size != 1 || providers.single().type().name != staged.providerClass || providers.single().type().classLoader !== loader) {
                        throw PluginHostException("PLUGIN_INVALID_PROVIDER", "DiagnosticPlugin must have exactly one package-local provider")
                    }
                    val instance = providers.single().get()
                    try {
                        if (instance.id != staged.item.manifest.id || instance.version != staged.item.manifest.version || instance.apiVersion != PluginApi.VERSION) {
                            throw PluginHostException("PLUGIN_INVALID_PROVIDER", "DiagnosticPlugin identity/API does not match its package")
                        }
                    } catch (error: Throwable) {
                        try { instance.close() } catch (_: Exception) { /* Keep the original admission failure. */ }
                        throw error
                    }
                    LoadedPlugin(staged, instance, loader, grants.toSet(), configurationSnapshot, disableGeneration)
                } catch (error: Throwable) {
                    closeLoader(loader)
                    throw error
                }
            }
        } catch (error: Throwable) {
            // A timed-out running constructor still owns its loader. Its eventual result/error closes
            // it on the worker; only a task that never started is safe to close here.
            if (loaderClaimed.compareAndSet(false, true)) closeLoader(loader)
            throw error
        }
    }

    private suspend fun packageFor(installation: PluginInstallation): StagedPluginPackage {
        val selection = Selection(installation)
        return metadata[selection] ?: withContext(Dispatchers.IO) {
            if (installation.installed && !installer.hasVersion(installation)) {
                // Repair lost staging only from the exact still-trusted pin. Never overwrite damaged
                // existing immutable data, substitute a newer catalogue version or revive a tombstone.
                installer.stage(pinnedPackage(installation))
            } else installer.read(installation)
        }.also { metadata[selection] = it }
    }

    private fun pinnedPackage(installation: PluginInstallation): ValidatedPluginPackage {
        PluginValidation.digest(installation.sha256)
        return catalog.select(installation.id, installation.version).also {
            if (!it.item.sha256.equals(installation.sha256, ignoreCase = true)) {
                throw PluginHostException("PLUGIN_HASH_MISMATCH", "Trusted catalogue package differs from the recorded immutable selection")
            }
        }
    }

    private fun configuration(staged: StagedPluginPackage, values: JsonObject): JsonObject = configuration(staged.item, values)

    private fun configuration(item: PluginCatalogItem, values: JsonObject): JsonObject = try {
        PluginValidation.configuration(item.manifest.configuration, values)
    } catch (error: PluginHostException) {
        throw PluginHostException("PLUGIN_INVALID_CONFIGURATION", error.message ?: "Invalid plugin configuration")
    }

    private suspend fun find(id: String): PluginInstallation = store.find(id)?.also(::observe) ?: run {
        retireMissing(id)
        throw PluginHostException("PLUGIN_NOT_INSTALLED", "No plugin installation or tombstone exists for this id", 404)
    }

    private suspend fun persist(next: PluginInstallation, previous: PluginInstallation?) {
        checkOpen()
        val saved = if (previous == null) store.insertIfAbsent(next) else store.save(next, previous.revision)
        if (!saved) throw PluginHostException("PLUGIN_REVISION_CONFLICT", "Plugin state changed concurrently; refresh and try again", 409)
    }

    private fun observe(installation: PluginInstallation) {
        val loaded = active[installation.id] ?: return
        if (!installation.installed || !installation.desiredEnabled || installation.disableGeneration != loaded.disableGeneration) loaded.admission.set(false)
        if (sameSelection(installation, loaded)) {
            if (!installation.approvedPermissions.containsAll(loaded.staged.item.manifest.permissions)) loaded.admission.set(false)
            // Shared-store configuration changes are immediate only for the same immutable version.
            try {
                loaded.configuration = configuration(loaded.staged, installation.configuration)
            } catch (error: PluginHostException) {
                loaded.admission.set(false)
                loadErrors[Selection(installation)] = error.message ?: "Invalid active configuration"
            }
        }
    }

    private fun closeMissing(installations: List<PluginInstallation>) {
        val ids = installations.mapTo(HashSet()) { it.id }
        active.keys.filter { it !in ids }.forEach(::retireMissing)
    }

    private fun retireMissing(id: String) {
        synchronized(lifecycleLock) {
            active.remove(id)?.let { dispose(it) }
        }
    }

    private fun admitted(installation: PluginInstallation, loaded: LoadedPlugin): Boolean = !closed.get() && installation.installed && installation.desiredEnabled && installation.disableGeneration == loaded.disableGeneration && loaded.admissionOpen()

    private fun requireAdmission(installation: PluginInstallation, loaded: LoadedPlugin, expectedVersion: String) {
        if (!admitted(installation, loaded)) throw PluginHostException("PLUGIN_DISABLED", "Plugin admission is disabled", 409)
        if (loaded.staged.item.manifest.version != expectedVersion) throw PluginHostException("PLUGIN_ACTIVE_VERSION_CONFLICT", "The requested active plugin version is stale; refresh the UI", 409)
    }

    private fun sameSelection(installation: PluginInstallation, loaded: LoadedPlugin): Boolean = installation.version == loaded.staged.item.manifest.version && installation.sha256.equals(loaded.staged.item.sha256, ignoreCase = true)

    private suspend fun render(installation: PluginInstallation): PluginRuntimeState {
        observe(installation)
        val selection = Selection(installation)
        val desired = try {
            packageFor(installation).item
        } catch (error: PluginHostException) {
            if (installation.installed) loadErrors.putIfAbsent(selection, error.message ?: "Installed package could not be read")
            // A removed/lost package may still have trusted, exactly pinned management metadata.
            // This never populates the loaded-package cache and never re-enables a tombstone.
            try { withContext(Dispatchers.IO) { pinnedPackage(installation).item } } catch (_: PluginHostException) { null }
        }
        val loaded = active[installation.id]
        val admitted = loaded != null && admitted(installation, loaded)
        val requiresRestart = installation.installed && (
            (installation.desiredEnabled && !admitted) || (loaded != null && !sameSelection(installation, loaded))
        )
        val error = loadErrors[selection]
        val status = when {
            !installation.installed -> "uninstalled"
            error != null -> "error"
            !installation.desiredEnabled -> "disabled"
            requiresRestart -> "pending-restart"
            admitted -> "active"
            else -> "disabled"
        }
        return PluginRuntimeState(
            id = installation.id,
            displayName = desired?.manifest?.displayName ?: loaded?.staged?.item?.manifest?.displayName ?: installation.id,
            description = desired?.manifest?.description ?: "",
            source = installation.source, installed = installation.installed, installedVersion = installation.version,
            installedSha256 = installation.sha256,
            activeVersion = loaded?.staged?.item?.manifest?.version,
            desiredEnabled = installation.desiredEnabled, admissionEnabled = admitted, requiresRestart = requiresRestart,
            status = status, loadError = error, revision = installation.revision,
            permissions = desired?.manifest?.permissions?.toList() ?: emptyList(), approvedPermissions = installation.approvedPermissions.toList(),
            configuration = PluginValidation.snapshot(installation.configuration),
            activeConfiguration = loaded?.configuration?.let(PluginValidation::snapshot),
            configurationFields = desired?.manifest?.configuration ?: emptyList(),
            activeUi = if (admitted) loaded.staged.item.ui else null,
        )
    }

    private fun bootstrapState(id: String, error: String): PluginRuntimeState = PluginRuntimeState(
        id = id, displayName = id, description = "", source = "bundled", installed = false,
        installedVersion = null, activeVersion = null, desiredEnabled = false, admissionEnabled = false,
        requiresRestart = false, status = "error", loadError = "Optional plugin bootstrap failed: $error".take(4_000),
    )

    private fun plan(plan: ProbePlan, limits: ProbeLimits): ProbePlan {
        val prompt = plan.prompt.trim()
        if (prompt.isBlank() || prompt.length > limits.maxPromptChars || prompt.contains('\u0000') || plan.maxOutputTokens <= 0 || plan.timeoutMs <= 0) {
            throw PluginHostException("PLUGIN_INVALID_PLAN", "Plugin returned an invalid probe plan")
        }
        return ProbePlan(prompt, minOf(plan.maxOutputTokens, limits.maxOutputTokens), minOf(plan.timeoutMs, limits.timeoutMs))
    }

    private fun limits(limits: ProbeLimits): ProbeLimits {
        if (limits.maxPromptChars <= 0 || limits.maxOutputTokens <= 0 || limits.timeoutMs <= 0) throw PluginHostException("PLUGIN_INVALID_LIMITS", "Probe limits must be positive")
        return ProbeLimits(minOf(limits.maxPromptChars, 4_000), minOf(limits.maxOutputTokens, 128), minOf(limits.timeoutMs, 45_000))
    }

    private fun outcome(outcome: ProbeOutcome): ProbeOutcome = outcome.copy(
        latencyMs = outcome.latencyMs.coerceAtLeast(0), responseText = outcome.responseText?.filterNot { it == '\u0000' }?.take(16_000),
        errorMessage = outcome.errorMessage?.take(4_000), upstreamReportedModel = outcome.upstreamReportedModel?.take(256),
        usage = outcome.usage?.takeIf { it.toString().length <= 16_000 }?.let(PluginValidation::snapshot),
    )

    private fun report(report: DiagnosticReport): DiagnosticReport {
        fun invalidReport(): Nothing = throw PluginHostException("PLUGIN_INVALID_REPORT", "Plugin returned a report exceeding the declarative result limits")
        fun <T> boundedCopy(items: List<T>, limit: Int): List<T> {
            if (items.size > limit) invalidReport()
            val copy = ArrayList<T>(limit)
            for (item in items) {
                if (copy.size == limit) invalidReport()
                copy.add(item)
            }
            return copy
        }
        // A plugin can change its size or iterator between reads. Bound the copy itself and
        // validate only the host-owned lists that will be returned to the caller.
        val snapshot = report.copy(metrics = boundedCopy(report.metrics, 32), notices = boundedCopy(report.notices, 16))
        if (snapshot.title.length > 256 || snapshot.title.isBlank() || snapshot.metrics.any { it.label.length > 128 || it.value.length > 512 } || snapshot.notices.any { it.length > 2_000 } || (snapshot.text?.length ?: 0) > 16_000) {
            invalidReport()
        }
        return snapshot
    }

    private suspend fun <T> withProbePermit(planTimeoutMs: Long, block: suspend () -> T): T {
        var acquired = false
        try {
            val admitted = withTimeoutOrNull(minOf(PluginExecutionBudgets.ADMISSION_WAIT_MS, planTimeoutMs)) {
                probeWorkers.acquire()
                acquired = true
                true
            }
            if (admitted != true) throw PluginHostException("PLUGIN_BUSY", "Timed out waiting for diagnostic probe capacity", 503)
            return block()
        } finally {
            // Covers cancellation after acquire but before the timed acquisition returns as well.
            if (acquired) probeWorkers.release()
        }
    }

    private fun <T> withLoader(loader: ClassLoader, block: () -> T): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        try {
            thread.contextClassLoader = loader
            return block()
        } finally {
            thread.contextClassLoader = previous
        }
    }

    /** Cancellation returns at the deadline even if a trusted blocking implementation ignores it. */
    @Suppress("DEPRECATION") // Fatal ThreadDeath must not become a normal plugin execution failure.
    private suspend fun <T : Any> runCode(
        operation: String, timeoutMs: Long, loader: ClassLoader = parentClassLoader,
        onDiscard: (T) -> Unit = {}, owner: LoadedPlugin? = null, block: () -> T,
    ): T {
        val ownership = CodeResultOwnership(onDiscard)
        try {
            val completed = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    try {
                        val future = codeWorkers.submit {
                            var entered = false
                            try {
                                if (continuation.isActive) {
                                    // The worker and disposer share this start gate. Queued/cancelled
                                    // tasks own nothing, while a running task retains the loader even
                                    // after its continuation times out or its Future is cancelled.
                                    if (owner != null) {
                                        if (!owner.beginWork()) throw PluginHostException("PLUGIN_RUNTIME_CLOSED", "Plugin is closing", 503)
                                        entered = true
                                    }
                                    ownership.offer(withLoader(loader, block))
                                    continuation.resume(Unit, onCancellation = { _, _, _ -> ownership.discard() })
                                }
                            } catch (error: Throwable) {
                                if (error is VirtualMachineError || error is ThreadDeath) throw error
                                val safe = if (error is PluginHostException) error else PluginHostException("PLUGIN_CODE_FAILED", "Plugin $operation failed (${error.javaClass.simpleName})", 503)
                                continuation.resumeWithException(safe)
                            } finally {
                                if (entered && owner!!.finishWork()) closeLoader(owner.loader)
                            }
                        }
                        continuation.invokeOnCancellation {
                            future.cancel(true)
                            codeWorkers.remove(future as Runnable)
                        }
                    } catch (_: RejectedExecutionException) {
                        continuation.resumeWithException(PluginHostException("PLUGIN_BUSY", "Plugin code workers are unavailable", 503))
                    }
                }
                true
            }
            if (completed != true) throw PluginHostException("PLUGIN_CODE_TIMEOUT", "Plugin $operation exceeded its time bound", 503)
            currentCoroutineContext().ensureActive()
            // The continuation delivers only a signal. Ownership transfers outside the outer
            // timeout, so a deadline racing with continuation delivery still disposes the value.
            return ownership.take() ?: throw PluginHostException("PLUGIN_CODE_TIMEOUT", "Plugin $operation exceeded its time bound", 503)
        } finally {
            ownership.discard()
        }
    }

    private fun dispose(loaded: LoadedPlugin): Future<*>? {
        loaded.admission.set(false)
        if (!loaded.startDisposal()) return null
        return cleanupWorkers.submit {
            try {
                // DiagnosticPlugin.close is a cooperative stop signal and may overlap plan/report
                // by SDK contract. The loader remains owned until close AND real workers finish.
                withLoader(loaded.loader) { loaded.plugin.close() }
            } finally {
                if (loaded.finishDisposal()) closeLoader(loaded.loader)
            }
        }
    }

    private fun closeLoader(loader: PackageClassLoader) {
        try { loader.close() } catch (_: Exception) { /* Continue closing independent resources. */ }
    }

    override fun close() {
        val loaded = synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            active.values.toList().also { plugins ->
                plugins.forEach { it.admission.set(false) }
                active.clear()
            }
        }
        val futures = loaded.mapNotNull { dispose(it) }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CODE_TIMEOUT_MS)
        futures.forEach { future ->
            try {
                future.get(maxOf(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
                // Do not cancel queued disposers: cooperative providers still close after the
                // bounded caller wait, even if another provider is slow or failed.
            }
        }
        codeWorkers.shutdownNow()
    }

    private fun checkOpen() {
        if (closed.get()) throw PluginHostException("PLUGIN_RUNTIME_CLOSED", "Plugin runtime is closed", 503)
    }

    private fun checkReady() {
        checkOpen()
        if (!initialized) throw PluginHostException("PLUGIN_RUNTIME_NOT_INITIALIZED", "Plugin runtime has not been initialized", 503)
    }

    private fun validateExpectedRevision(revision: Long) {
        if (revision !in 0 until Long.MAX_VALUE) {
            throw PluginHostException("PLUGIN_INVALID_REVISION", "Expected revision must be a nonnegative writable revision")
        }
    }

    private fun nextRevision(previous: PluginInstallation?): Long {
        if (previous == null) return 0
        if (previous.revision < 0 || previous.revision == Long.MAX_VALUE) throw PluginHostException("PLUGIN_REVISION_CONFLICT", "Invalid plugin state revision", 409)
        return previous.revision + 1
    }

    private fun nextDisableGeneration(previous: PluginInstallation): Long {
        if (previous.disableGeneration < 0 || previous.disableGeneration == Long.MAX_VALUE) {
            throw PluginHostException("PLUGIN_REVISION_CONFLICT", "Invalid plugin disable generation", 409)
        }
        return previous.disableGeneration + 1
    }

    private fun now(previous: PluginInstallation?): Long = maxOf(System.currentTimeMillis(), (previous?.updatedAt ?: 0).let { if (it == Long.MAX_VALUE) it else it + 1 })
    private fun elapsed(started: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).coerceAtLeast(0)

    // Distinguish candidate validation in getOrCreate from legacy-read or store failures.
    private class BootstrapCandidateConfigurationFailure(val validation: PluginHostException) : RuntimeException(validation)

    private data class Selection(val id: String, val version: String, val sha256: String) {
        constructor(installation: PluginInstallation) : this(installation.id, installation.version, installation.sha256.lowercase())
    }

    private class LoadedPlugin(
        val staged: StagedPluginPackage, val plugin: DiagnosticPlugin, val loader: PackageClassLoader,
        val grants: Set<String>, var configuration: JsonObject, val disableGeneration: Long, val admission: AtomicBoolean = AtomicBoolean(true),
    ) {
        val pendingStops = AtomicInteger()
        fun admissionOpen(): Boolean = pendingStops.get() == 0 && admission.get()

        private var workers = 0
        private var disposalStarted = false
        private var providerClosed = false
        private var loaderReleased = false

        @Synchronized
        fun beginWork(): Boolean {
            if (disposalStarted) return false
            workers++
            return true
        }

        @Synchronized
        fun finishWork(): Boolean {
            check(workers > 0)
            workers--
            return releaseLoader()
        }

        @Synchronized
        fun startDisposal(): Boolean {
            if (disposalStarted) return false
            disposalStarted = true
            return true
        }

        @Synchronized
        fun finishDisposal(): Boolean {
            providerClosed = true
            return releaseLoader()
        }

        private fun releaseLoader(): Boolean {
            if (!providerClosed || workers != 0 || loaderReleased) return false
            loaderReleased = true
            return true
        }
    }

    /** Exactly one caller claims a result, or its producer/cancellation path disposes it. */
    internal class CodeResultOwnership<T : Any>(private val onDiscard: (T) -> Unit) {
        private var value: T? = null
        private var released = false

        fun offer(result: T) {
            val discard = synchronized(this) {
                if (released) true
                else {
                    check(value == null)
                    value = result
                    false
                }
            }
            if (discard) onDiscard(result)
        }

        fun take(): T? = synchronized(this) {
            if (released) null
            else {
                released = true
                value.also { value = null }
            }
        }

        fun discard() { take()?.let(onDiscard) }
    }

    private data class ExecutionSnapshot(val loaded: LoadedPlugin, val input: DiagnosticInput, val context: DiagnosticContext)

    private class PackageClassLoader(url: URL, parent: ClassLoader) : URLClassLoader(arrayOf(url), parent) {
        private val closeStarted = AtomicBoolean(false)
        // Never discover app/legacy providers via parent resources. SDK types still delegate to parent.
        override fun getResources(name: String): Enumeration<URL> = if (name == PluginPackages.SERVICE_PATH) findResources(name) else super.getResources(name)
        override fun getResource(name: String): URL? = if (name == PluginPackages.SERVICE_PATH) findResource(name) else super.getResource(name)
        override fun close() { if (closeStarted.compareAndSet(false, true)) super.close() }
    }

    private companion object {
        const val CODE_TIMEOUT_MS = 2_000L
    }
}

/** Preparation is bounded by the native adapter; plan.timeoutMs is the separate HTTP limit. */
internal object PluginExecutionBudgets {
    const val ADMISSION_WAIT_MS = 2_000L
    const val OAUTH_PREPARATION_MS = 20_000L

    fun callbackTimeoutMs(contextKind: String, planTimeoutMs: Long): Long {
        PluginValidation.requireData(planTimeoutMs in 1..45_000, "Invalid callback HTTP timeout")
        return when (contextKind) {
            "channel" -> planTimeoutMs
            "oauth-account" -> planTimeoutMs + OAUTH_PREPARATION_MS
            else -> PluginValidation.invalid("Unsupported callback context")
        }
    }
}
