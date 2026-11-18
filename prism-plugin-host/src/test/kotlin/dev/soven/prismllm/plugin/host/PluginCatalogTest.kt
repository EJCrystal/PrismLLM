package dev.soven.prismllm.plugin.host

import dev.soven.prismllm.plugin.sdk.PluginApi
import dev.soven.prismllm.plugin.sdk.PluginField
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("unit")
internal class PluginCatalogTest : PluginTestSupport() {
    private suspend fun rejectedPackage(code: String? = null) {
        val catalog = PluginCatalog(catalogDirectory)
        val item = catalog.items().single()
        assertFalse(item.compatible)
        assertTrue(item.ui.entries.isEmpty() && item.manifest.configuration.isEmpty() && item.manifest.permissions.isEmpty())
        val reason = assertNotNull(item.compatibilityReason)
        assertTrue(reason.isNotBlank() && reason.length <= 4_000)
        val error = failure(code) { catalog.select(item.manifest.id, item.manifest.version) }
        assertTrue(reason.contains(error.code))
    }

    @Test
    fun `missing deployment catalogue is an empty catalogue`() {
        assertEquals(emptyList(), PluginCatalog(catalogDirectory).items())
    }

    @Test
    fun `catalogue validates real SPI packages without initializing code`() {
        val packaged = fixture()
        publish(packaged)
        val item = PluginCatalog(catalogDirectory).items().single()
        assertEquals(packaged.manifest, item.manifest)
        assertEquals(packaged.ui, item.ui)
        assertEquals(packaged.sha256, item.sha256)
        assertEquals("bundled", item.source)
        assertTrue(item.compatible)
        assertEquals(0, packaged.count("initialized"))
    }

    @Test
    fun `package ids allow 80 characters but reject 81 before any store write while versions stay bounded to 64`(): Unit = runBlocking {
        val id = "a".repeat(80)
        val version = "1.0.0+" + "a".repeat(58)
        val accepted = fixture(id = id, version = version)
        publish(accepted)
        assertEquals(id, PluginCatalog(catalogDirectory).items().single().manifest.id)
        val store = MemoryInstallationStore()
        val host = runtime(store)
        host.initialize()
        assertTrue(host.install(id, version, accepted.manifest.permissions).installed)
        val writes = store.writes
        val rejected = listOf(fixture(id = id + "a"), fixture(version = version + "a"))
        rejected.forEach { packaged ->
            publish(packaged)
            failure("PLUGIN_INVALID_PACKAGE") { PluginCatalog(catalogDirectory).items() }
            failure("PLUGIN_INVALID_PACKAGE") { host.install(packaged.manifest.id, packaged.manifest.version, packaged.manifest.permissions) }
            assertEquals(writes, store.writes)
            assertEquals(0, packaged.count("initialized"))
        }
        assertEquals(listOf(id), store.list().map { it.id })
        assertEquals(0, accepted.count("initialized"))
    }

    @Test
    fun `entry action and field identifiers keep their existing 64 character bounds`(): Unit = runBlocking {
        val packaged = fixture()
        val entry = packaged.ui.entries.first()
        val identifier = "a".repeat(64)
        val bounded = entry.copy(id = identifier, action = entry.action.copy(id = identifier, fields = listOf(PluginField(identifier, "Field", "text"))))
        assertEquals(null, PluginValidation.descriptors(packaged.manifest, packaged.ui.copy(entries = listOf(bounded))))
        val invalid = listOf(
            bounded.copy(id = identifier + "a"),
            bounded.copy(action = bounded.action.copy(id = identifier + "a")),
            bounded.copy(action = bounded.action.copy(fields = listOf(PluginField(identifier + "a", "Field", "text")))),
        )
        invalid.forEach { candidate -> failure("PLUGIN_INVALID_PACKAGE") { PluginValidation.descriptors(packaged.manifest, packaged.ui.copy(entries = listOf(candidate))) } }
    }

    @Test
    fun `hash and catalogue manifest identity must match`(): Unit = runBlocking {
        val packaged = fixture()
        publish(packaged)
        Files.write(catalogDirectory.resolve(packaged.fileName), packaged.archive + 0)
        rejectedPackage("PLUGIN_HASH_MISMATCH")
        val otherIdentity = packaged.manifest.copy(id = "other.probe")
        val replaced = packaged.repack(packaged.entries.map { if (it.name == "manifest.json") it.copy(bytes = PluginValidation.json.encodeToString(otherIdentity).toByteArray()) else it })
        publish(replaced)
        rejectedPackage("PLUGIN_INVALID_PACKAGE")
        assertEquals(0, packaged.count("initialized"))
    }

    @Test
    fun `incompatible API or unknown capability can be displayed but not installed`(): Unit = runBlocking {
        val api = fixture(apiVersion = 2)
        val capability = fixture(id = "unknown.capability", permissions = listOf(PluginApi.CHANNEL_PROBE, "arbitrary.permission"))
        publish(api, capability)
        val items = PluginCatalog(catalogDirectory).items()
        assertTrue(items.all { !it.compatible && !it.compatibilityReason.isNullOrBlank() })
        val store = MemoryInstallationStore()
        val host = runtime(store)
        host.initialize()
        failure("PLUGIN_INCOMPATIBLE") { host.install(api.manifest.id, "1.0.0", api.manifest.permissions) }
        failure("PLUGIN_INCOMPATIBLE") { host.install(capability.manifest.id, "1.0.0", capability.manifest.permissions) }
        assertEquals(0, store.writes)
        assertTrue(store.list().isEmpty())
        assertFalse(Files.exists(managedDirectory))
        assertEquals(0, api.count("initialized"))
        assertEquals(0, capability.count("initialized"))
    }

    @Test
    fun `catalogue forbids path escapes links duplicate selections and unsupported schema`(): Unit = runBlocking {
        val packaged = fixture()
        publish(packaged)
        val catalogPath = catalogDirectory.resolve("catalog.json")
        val original = Files.readString(catalogPath)
        Files.writeString(catalogPath, original.replace(packaged.fileName, "../outside.prismplugin"))
        failure { PluginCatalog(catalogDirectory).items() }
        Files.writeString(catalogPath, original.replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
        failure { PluginCatalog(catalogDirectory).items() }
        publish(packaged, packaged)
        failure { PluginCatalog(catalogDirectory).items() }
        publish(packaged)
        val external = root.resolve("outside.prismplugin")
        Files.write(external, packaged.archive)
        Files.delete(catalogDirectory.resolve(packaged.fileName))
        Files.createSymbolicLink(catalogDirectory.resolve(packaged.fileName), external)
        rejectedPackage()
    }

    @Test
    fun `ZIP allowlist rejects traversal duplicates links special files and extra executable assets`(): Unit = runBlocking {
        val packaged = fixture()
        val unsafe = listOf(
            packaged.entries + ZipFixtureEntry("../escaped", byteArrayOf(1)),
            packaged.entries + ZipFixtureEntry("/absolute", byteArrayOf(1)),
            packaged.entries + ZipFixtureEntry("assets\\escape", byteArrayOf(1)),
            packaged.entries + ZipFixtureEntry("manifest.json", packaged.manifestBytes),
            packaged.entries.map { if (it.name == "plugin.jar") it.copy(mode = 0xa1ff) else it },
            packaged.entries.map { if (it.name == "plugin.jar") it.copy(mode = 0x11a4) else it },
            packaged.entries + ZipFixtureEntry("install.sh", "exit 0".toByteArray()),
            packaged.entries.filter { it.name != "ui.json" },
        )
        for (entries in unsafe) {
            publish(packaged.repack(entries))
            rejectedPackage("PLUGIN_INVALID_PACKAGE")
        }
        assertFalse(Files.exists(root.resolve("escaped")))
        assertEquals(0, packaged.count("initialized"))
    }

    @Test
    fun `archive bounds prefixes trailers and metadata expansion are checked`(): Unit = runBlocking {
        val packaged = fixture()
        val archives = listOf(
            packaged.archive + "trailer".toByteArray(),
            "#!/bin/sh\n".toByteArray() + packaged.archive,
            zipFixture(packaged.entries.map { if (it.name == "manifest.json") it.copy(bytes = ByteArray(PluginValidation.MAX_METADATA_BYTES + 1)) else it }),
        )
        archives.forEach { archive ->
            publish(packaged.copy(archive = archive))
            rejectedPackage()
        }
    }

    @Test
    fun `local and central ZIP entry names cannot disagree`(): Unit = runBlocking {
        val packaged = fixture()
        val archive = packaged.archive.copyOf()
        // First local name begins at byte 30. The central directory still says manifest.json.
        archive[30] = 'x'.code.toByte()
        publish(packaged.copy(archive = archive))
        rejectedPackage()
        val flags = packaged.archive.copyOf()
        flags[6] = (flags[6].toInt() or 1).toByte() // Local encryption bit, absent centrally.
        publish(packaged.copy(archive = flags))
        rejectedPackage()
    }

    @Test
    fun `strict metadata rejects duplicate and unknown JSON keys and excessive nesting`(): Unit = runBlocking {
        val packaged = fixture()
        val text = packaged.manifestBytes.toString(Charsets.UTF_8)
        val documents = listOf(
            text.dropLast(1) + ",\"id\":\"example.probe\"}",
            text.dropLast(1) + ",\"\\u0069d\":\"example.probe\"}",
            text.dropLast(1) + ",\"callback\":\"https://not-allowed.invalid\"}",
            "[".repeat(33) + "0" + "]".repeat(33),
        )
        for (document in documents) {
            publish(packaged.repack(packaged.entries.map { if (it.name == "manifest.json") it.copy(bytes = document.toByteArray()) else it }))
            rejectedPackage()
        }
        val invalidUtf8 = byteArrayOf(0xc3.toByte(), 0x28)
        publish(packaged.repack(packaged.entries.map { if (it.name == "manifest.json") it.copy(bytes = invalidUtf8) else it }))
        rejectedPackage()
    }

    @Test
    fun `declarative UI and field defaults are validated before any install`(): Unit = runBlocking {
        val packaged = fixture()
        val entry = packaged.ui.entries.first()
        val actions = listOf(
            entry.action.copy(resource = "https://not-allowed.invalid"),
            entry.action.copy(resultKind = "html"),
            entry.action.copy(fields = listOf(PluginField("prompt", "Prompt", "javascript"))),
            entry.action.copy(fields = listOf(PluginField("endpoint", "Endpoint", "text"))),
            entry.action.copy(fields = listOf(PluginField("chosenModel", "Model", "model"))),
            entry.action.copy(fields = listOf(PluginField("chosenCredential", "Credential", "credential"))),
            entry.action.copy(fields = listOf(PluginField("chosenProtocol", "Protocol", "protocol"))),
            entry.action.copy(fields = listOf(PluginField("chosenScope", "Scope", "scope"))),
            entry.action.copy(fields = listOf(PluginField("model", "Model", "text"))),
            entry.action.copy(fields = listOf(PluginField("flag", "Flag", "boolean", defaultValue = JsonPrimitive("true")))),
            entry.action.copy(fields = listOf(PluginField("count", "Count", "number", min = 2.0, max = 1.0))),
            entry.action.copy(fields = List(33) { PluginField("field$it", "Field", "text") }),
        )
        actions.forEach { action ->
            val ui = packaged.ui.copy(entries = listOf(entry.copy(action = action)))
            publish(packaged.repack(packaged.entries.map { if (it.name == "ui.json") it.copy(bytes = PluginValidation.json.encodeToString(ui).toByteArray()) else it }))
            rejectedPackage()
        }
        assertEquals(0, packaged.count("initialized"))
    }

    @Test
    fun `JAR validation disallows external classpaths SDK forks and nonlocal SPI declarations`(): Unit = runBlocking {
        val packaged = fixture()
        val original = ZipFile.builder().setSeekableByteChannel(SeekableInMemoryByteChannel(packaged.jar)).get().use { zip ->
            zip.entries.asSequence().map { entry -> ZipFixtureEntry(entry.name, zip.getInputStream(entry).use { it.readBytes() }) }.toList()
        }
        val jars = listOf(
            original.map { if (it.name == "META-INF/MANIFEST.MF") it.copy(bytes = "Manifest-Version: 1.0\r\nClass-Path: https://not-allowed.invalid/remote.jar\r\n\r\n".toByteArray()) else it },
            original + ZipFixtureEntry("META-INF/INDEX.LIST", "JarIndex-Version: 1.0".toByteArray()),
            original + ZipFixtureEntry("dev/soven/prismllm/plugin/sdk/DiagnosticPlugin.class", byteArrayOf(1)),
            original + ZipFixtureEntry("META-INF/versions/21/dev/soven/prismllm/plugin/sdk/DiagnosticPlugin.class", byteArrayOf(1)),
            original + ZipFixtureEntry("../escape.class", byteArrayOf(1)),
            original.filter { it.name != PluginPackages.SERVICE_PATH },
            original.map { if (it.name == PluginPackages.SERVICE_PATH) it.copy(bytes = "${packaged.providerClass}\nmissing.Provider\n".toByteArray()) else it },
            original.map { if (it.name == PluginPackages.SERVICE_PATH) it.copy(bytes = "missing.Provider\n".toByteArray()) else it },
        )
        jars.forEach { entries ->
            val jar = zipFixture(entries)
            publish(packaged.repack(packaged.entries.map { if (it.name == "plugin.jar") it.copy(bytes = jar) else it }))
            rejectedPackage()
        }
        assertEquals(0, packaged.count("initialized"))
    }

    @Test
    fun `validated staging is immutable idempotent and never loads classes`(): Unit = runBlocking {
        val packaged = fixture()
        publish(packaged)
        val selected = PluginCatalog(catalogDirectory).select(packaged.manifest.id, packaged.manifest.version)
        val installer = PluginPackageInstaller(managedDirectory)
        val staged = installer.stage(selected)
        val timestamp = Files.getLastModifiedTime(staged.jarPath)
        val replay = installer.stage(selected)
        assertEquals(staged.jarPath, replay.jarPath)
        assertEquals(timestamp, Files.getLastModifiedTime(replay.jarPath))
        assertTrue(Files.readAllBytes(staged.jarPath).contentEquals(packaged.jar))
        assertFalse(PosixFilePermission.OWNER_WRITE in Files.getPosixFilePermissions(staged.jarPath))
        assertEquals(0, packaged.count("initialized"))
        assertEquals(listOf(packaged.manifest.version), Files.list(staged.jarPath.parent.parent).use { files -> files.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `concurrent staging of the same immutable package converges to one directory`(): Unit = runBlocking {
        val packaged = fixture()
        publish(packaged)
        val selected = PluginCatalog(catalogDirectory).select(packaged.manifest.id, "1.0.0")
        val staged = coroutineScope {
            List(4) { async(Dispatchers.IO) { PluginPackageInstaller(managedDirectory).stage(selected) } }.map { it.await() }
        }
        assertEquals(1, staged.map { it.jarPath }.distinct().size)
        assertTrue(Files.readAllBytes(staged.first().jarPath).contentEquals(packaged.jar))
    }

    @Test
    fun `a different digest cannot replace an occupied version`(): Unit = runBlocking {
        val original = fixture()
        publish(original)
        val installer = PluginPackageInstaller(managedDirectory)
        val staged = installer.stage(PluginCatalog(catalogDirectory).select(original.manifest.id, "1.0.0"))
        val replacement = fixture()
        publish(replacement)
        val error = failure("PLUGIN_VERSION_CONFLICT") { installer.stage(PluginCatalog(catalogDirectory).select(replacement.manifest.id, "1.0.0")) }
        assertEquals(409, error.statusCode)
        assertTrue(Files.readAllBytes(staged.jarPath).contentEquals(original.jar))
        assertEquals(0, replacement.count("initialized"))
    }

    @Test
    fun `managed staging rejects symlink roots and unapproved selections`(): Unit = runBlocking {
        val packaged = fixture()
        publish(packaged)
        val host = runtime()
        host.initialize()
        failure("PLUGIN_PACKAGE_NOT_FOUND") { host.install(packaged.manifest.id, "9.0.0", packaged.manifest.permissions) }
        val external = root.resolve("external-state")
        Files.createDirectory(external)
        Files.createSymbolicLink(managedDirectory, external)
        failure { host.install(packaged.manifest.id, "1.0.0", packaged.manifest.permissions) }
        assertEquals(0L, Files.list(external).use { it.count() })
        assertNotNull(host)
    }
}
