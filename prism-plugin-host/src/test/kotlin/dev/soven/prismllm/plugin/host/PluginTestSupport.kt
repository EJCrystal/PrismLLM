package dev.soven.prismllm.plugin.host

import dev.soven.prismllm.plugin.sdk.PluginAction
import dev.soven.prismllm.plugin.sdk.PluginApi
import dev.soven.prismllm.plugin.sdk.PluginChoice
import dev.soven.prismllm.plugin.sdk.PluginField
import dev.soven.prismllm.plugin.sdk.PluginManifest
import dev.soven.prismllm.plugin.sdk.PluginUiDescriptor
import dev.soven.prismllm.plugin.sdk.PluginUiEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal data class ZipFixtureEntry(val name: String, val bytes: ByteArray, val mode: Int = 0x81a4)

internal fun zipFixture(entries: List<ZipFixtureEntry>): ByteArray = ByteArrayOutputStream().use { bytes ->
    ZipArchiveOutputStream(bytes).use { zip ->
        entries.forEach {
            val entry = ZipArchiveEntry(it.name).apply { unixMode = it.mode }
            zip.putArchiveEntry(entry)
            zip.write(it.bytes)
            zip.closeArchiveEntry()
        }
    }
    bytes.toByteArray()
}

internal data class PackageFixture(
    val manifest: PluginManifest,
    val ui: PluginUiDescriptor,
    val manifestBytes: ByteArray,
    val uiBytes: ByteArray,
    val jar: ByteArray,
    val archive: ByteArray,
    val fileName: String,
    val providerClass: String,
    val property: String,
) {
    val sha256: String get() = PluginPackages.hash(archive)
    val entries: List<ZipFixtureEntry> get() = listOf(ZipFixtureEntry("manifest.json", manifestBytes), ZipFixtureEntry("ui.json", uiBytes), ZipFixtureEntry("plugin.jar", jar))
    fun repack(entries: List<ZipFixtureEntry>): PackageFixture = copy(archive = zipFixture(entries))
    fun count(event: String): Int = System.getProperty("$property.$event")?.toInt() ?: 0
}

internal class MemoryInstallationStore : PluginInstallationStore {
    private val records = LinkedHashMap<String, PluginInstallation>()
    val failNextSave = AtomicBoolean(false)
    var beforeInsert: ((PluginInstallation) -> Unit)? = null
    var beforeSave: ((PluginInstallation) -> Unit)? = null
    var afterSave: ((PluginInstallation) -> Unit)? = null
    var writes = 0
        private set

    override suspend fun list(): List<PluginInstallation> = synchronized(records) { records.values.toList() }
    override suspend fun find(id: String): PluginInstallation? = synchronized(records) { records[id] }
    override suspend fun insertIfAbsent(installation: PluginInstallation): Boolean = synchronized(records) {
        beforeInsert?.invoke(installation)
        if (records.containsKey(installation.id)) false
        else { records[installation.id] = installation; writes++; true }
    }
    override suspend fun save(installation: PluginInstallation, expectedRevision: Long): Boolean = synchronized(records) {
        beforeSave?.invoke(installation)
        if (failNextSave.getAndSet(false) || records[installation.id]?.revision != expectedRevision) false
        else {
            assertEquals(expectedRevision + 1, installation.revision)
            records[installation.id] = installation
            writes++
            afterSave?.invoke(installation)
            true
        }
    }
    fun seed(installation: PluginInstallation) = synchronized(records) { records[installation.id] = installation }
}

/** Compile genuine, package-local SPI implementations rather than injecting app-classpath mocks. */
internal abstract class PluginTestSupport {
    @TempDir
    lateinit var root: Path
    val catalogDirectory: Path get() = root.resolve("catalog")
    val managedDirectory: Path get() = root.resolve("state")
    private val runtimes = ArrayList<PluginRuntime>()
    private val properties = ArrayList<String>()

    fun runtime(store: PluginInstallationStore = MemoryInstallationStore(), parent: ClassLoader? = null): PluginRuntime =
        (if (parent == null) PluginRuntime(PluginCatalog(catalogDirectory), managedDirectory, store)
        else PluginRuntime(PluginCatalog(catalogDirectory), managedDirectory, store, parent)).also { runtimes.add(it) }

    fun fixture(
        version: String = "1.0.0",
        id: String = "example.probe",
        permissions: List<String> = listOf(PluginApi.CHANNEL_PROBE, PluginApi.OAUTH_ACCOUNT_PROBE),
        configuration: List<PluginField> = defaultConfiguration(),
        ui: PluginUiDescriptor = defaultUi(version),
        apiVersion: Int = 1,
        providerId: String = id,
        providerVersion: String = version,
        planPrefix: (String) -> String = { "" },
        reportPrefix: String = "",
        constructorPrefix: (String) -> String = { "" },
        closePrefix: (String) -> String = { "" },
        configKey: String = "defaultPrompt",
        planTokens: Int = 9_999,
        planTimeoutMs: Long = 999_999,
        jarEntries: List<ZipFixtureEntry> = emptyList(),
    ): PackageFixture {
        val sequence = fixtureSequence.incrementAndGet()
        val simpleName = "Provider$sequence"
        val provider = "fixtureplugins.$simpleName"
        val property = "prism.host.fixture.$sequence"
        properties.add(property)
        val compileDirectory = root.resolve("compiler-$sequence")
        val source = compileDirectory.resolve("source/fixtureplugins/$simpleName.java")
        val classes = compileDirectory.resolve("classes")
        Files.createDirectories(source.parent)
        Files.createDirectories(classes)
        Files.writeString(source, """
            package fixtureplugins;
            import dev.soven.prismllm.plugin.sdk.*;
            import kotlinx.serialization.json.*;
            import java.util.Collections;
            public final class $simpleName implements DiagnosticPlugin {
                private static void mark(String event) {
                    synchronized (System.getProperties()) {
                        String key = "$property." + event;
                        int n = Integer.parseInt(System.getProperty(key, "0"));
                        System.setProperty(key, Integer.toString(n + 1));
                    }
                }
                static { mark("initialized"); }
                public $simpleName() { mark("constructed"); ${constructorPrefix(property)} }
                public String getId() { return "$providerId"; }
                public String getVersion() { return "$providerVersion"; }
                public int getApiVersion() { return 1; }
                public ProbePlan plan(DiagnosticInput input, DiagnosticContext context) {
                    mark("planned");
                    JsonElement value = input.getValues().get("prompt");
                    if (!(value instanceof JsonPrimitive) || ((JsonPrimitive)value).getContent().trim().isEmpty()) {
                        value = context.getConfiguration().get("$configKey");
                    }
                    String prompt = value instanceof JsonPrimitive ? ((JsonPrimitive)value).getContent() : "fixture-default";
                    ${planPrefix(property)}
                    return new ProbePlan(prompt, $planTokens, ${planTimeoutMs}L);
                }
                public DiagnosticReport report(ProbePlan plan, ProbeOutcome outcome) {
                    mark("reported");
                    $reportPrefix
                    return new DiagnosticReport(outcome.getSuccess(), "Fixture $version", Collections.emptyList(), Collections.emptyList(), plan.getPrompt());
                }
                public void close() { mark("closed"); ${closePrefix(property)} }
            }
        """.trimIndent())
        val compiler = assertNotNull(ToolProvider.getSystemJavaCompiler(), "Host unit fixtures need a JDK")
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        compiler.getStandardFileManager(diagnostics, null, Charsets.UTF_8).use { files ->
            val sources = files.getJavaFileObjects(source.toFile())
            val options = listOf("--release", "21", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString())
            assertTrue(compiler.getTask(null, files, diagnostics, options, null, sources).call(), diagnostics.diagnostics.joinToString("\n"))
        }
        val manifest = PluginManifest(id, version, "Fixture $version", "Trusted test package", apiVersion = apiVersion, permissions = permissions, configuration = configuration)
        val manifestBytes = PluginValidation.json.encodeToString(manifest).toByteArray()
        val uiBytes = PluginValidation.json.encodeToString(ui).toByteArray()
        val jar = zipFixture(listOf(
            ZipFixtureEntry("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n".toByteArray()),
            ZipFixtureEntry(PluginPackages.SERVICE_PATH, "$provider\n".toByteArray()),
            ZipFixtureEntry(provider.replace('.', '/') + ".class", Files.readAllBytes(classes.resolve(provider.replace('.', '/') + ".class"))),
        ) + jarEntries)
        val archive = zipFixture(listOf(ZipFixtureEntry("manifest.json", manifestBytes), ZipFixtureEntry("ui.json", uiBytes), ZipFixtureEntry("plugin.jar", jar)))
        return PackageFixture(manifest, ui, manifestBytes, uiBytes, jar, archive, "fixture-$sequence.prismplugin", provider, property)
    }

    fun publish(vararg packages: PackageFixture) {
        Files.createDirectories(catalogDirectory)
        packages.forEach { Files.write(catalogDirectory.resolve(it.fileName), it.archive) }
        val catalog = buildJsonObject {
            put("schemaVersion", 1)
            put("packages", JsonArray(packages.map { fixture ->
                buildJsonObject {
                    put("id", fixture.manifest.id)
                    put("version", fixture.manifest.version)
                    put("file", fixture.fileName)
                    put("sha256", fixture.sha256)
                }
            }))
        }
        Files.writeString(catalogDirectory.resolve("catalog.json"), catalog.toString())
    }

    suspend fun failure(code: String? = null, action: suspend () -> Any?): PluginHostException {
        val error = assertFailsWith<PluginHostException> { action() }
        if (code != null) assertEquals(code, error.code)
        return error
    }

    suspend fun awaitProperty(key: String, expected: String = "true", timeoutMs: Long = 1_500) {
        withTimeout(timeoutMs) { while (System.getProperty(key) != expected) delay(5) }
    }

    @AfterEach
    fun cleanup() {
        runtimes.forEach { it.close() }
        val systemProperties = System.getProperties()
        // Ownership fixtures track loader objects as well as string-valued counters.
        properties.forEach { prefix -> systemProperties.keys.toList().filterIsInstance<String>().filter { it.startsWith("$prefix.") }.forEach { systemProperties.remove(it) } }
        if (Files.exists(root)) {
            Files.walk(root).use { paths -> paths.filter { Files.isDirectory(it) }.forEach { path ->
                try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------")) } catch (_: UnsupportedOperationException) { }
            } }
        }
    }

    companion object {
        private val fixtureSequence = AtomicInteger()
        fun defaultConfiguration(): List<PluginField> = listOf(PluginField("defaultPrompt", "Default prompt", "textarea", required = true, defaultValue = JsonPrimitive("请只回复 OK"), maxLength = 4_000))
        fun defaultUi(version: String = "1.0.0"): PluginUiDescriptor {
            val shared = listOf(
                PluginField("prompt", "Prompt", "textarea", maxLength = 4_000),
                PluginField("model", "Model", "model", required = true),
                PluginField("protocol", "Protocol", "protocol", required = true),
            )
            return PluginUiDescriptor(entries = listOf(
                PluginUiEntry("channel", "Channel $version", "channel-tests", listOf("channel"),
                    PluginAction("probe", "Probe $version", shared + listOf(
                        PluginField("keyId", "Credential", "credential"),
                        PluginField("scope", "Scope", "scope", defaultValue = JsonPrimitive("single"), options = listOf(PluginChoice("single", "Single"), PluginChoice("all", "All"))),
                    ), supportsBatch = true)),
                PluginUiEntry("account", "Account $version", "oauth-account-tests", listOf("oauth-account"),
                    PluginAction("probe", "Probe $version", shared)),
            ))
        }
    }
}
