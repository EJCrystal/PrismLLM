package dev.soven.prismllm.plugin.host

import dev.soven.prismllm.plugin.sdk.PluginApi
import dev.soven.prismllm.plugin.sdk.PluginChoice
import dev.soven.prismllm.plugin.sdk.PluginField
import dev.soven.prismllm.plugin.sdk.PluginUiEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    private fun little32ForTest(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toInt() and 0xff).toLong() or
            ((bytes[offset + 1].toInt() and 0xff).toLong() shl 8) or
            ((bytes[offset + 2].toInt() and 0xff).toLong() shl 16) or
            ((bytes[offset + 3].toInt() and 0xff).toLong() shl 24)

    @Test
    fun `missing deployment catalogue is an empty catalogue`() {
        assertEquals(emptyList(), PluginCatalog(catalogDirectory).items())
    }

    @Test
    fun `a dangling catalogue symlink is rejected rather than treated as absent`(): Unit = runBlocking {
        Files.createDirectories(catalogDirectory)
        Files.createSymbolicLink(catalogDirectory.resolve("catalog.json"), root.resolve("missing-catalog-target.json"))
        val catalog = PluginCatalog(catalogDirectory)
        failure("PLUGIN_INVALID_PACKAGE") { catalog.items() }
        failure("PLUGIN_INVALID_PACKAGE") { catalog.bootstrapPackage("example.probe") }
    }

    @Test
    fun `a catalogue directory without search permission reports an IO error`(): Unit = runBlocking {
        publish()
        val permissions = try {
            Files.getPosixFilePermissions(catalogDirectory)
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "POSIX permissions are not supported")
            return@runBlocking
        }
        try {
            Files.setPosixFilePermissions(catalogDirectory, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            val denied = try {
                Files.readAttributes(catalogDirectory.resolve("catalog.json"), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                false
            } catch (_: AccessDeniedException) {
                true
            }
            assumeTrue(denied, "The process bypasses directory search permissions")
            val catalog = PluginCatalog(catalogDirectory)
            failure("PLUGIN_CATALOG_UNREADABLE") { catalog.items() }
            failure("PLUGIN_CATALOG_UNREADABLE") { catalog.bootstrapPackage("example.probe") }
            failure("PLUGIN_CATALOG_UNREADABLE") { catalog.select("example.probe", "1.0.0") }
        } finally {
            Files.setPosixFilePermissions(catalogDirectory, permissions)
        }
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
        val invalidVersions = listOf("١.0.0", "1.0.0-01", "1.0.0-α", "1.0.0+build.")
        for (version in invalidVersions) {
            val invalid = fixture(version = version)
            publish(invalid)
            failure("PLUGIN_INVALID_PACKAGE") { PluginCatalog(catalogDirectory).items() }
            failure("PLUGIN_INVALID_PACKAGE") { host.install(invalid.manifest.id, version, invalid.manifest.permissions) }
            assertEquals(writes, store.writes)
        }
        assertEquals(listOf(id), store.list().map { it.id })
        assertEquals(0, accepted.count("initialized"))
    }

    @Test
    fun `bootstrap validates requested versions newest first and falls back without a second selection scan`(): Unit = runBlocking {
        val older = fixture(version = "1.8.0")
        val newer = fixture(version = "2.0.0", apiVersion = 2)
        val unrelated = fixture(id = "unrelated.probe", version = "9.0.0")
        publish(unrelated, older, newer)
        Files.write(catalogDirectory.resolve(unrelated.fileName), byteArrayOf(1))

        val selected = assertNotNull(PluginCatalog(catalogDirectory).bootstrapPackage(older.manifest.id))
        assertEquals(older.manifest.version, selected.item.manifest.version)
        assertTrue(selected.archive.contentEquals(older.archive))
        assertEquals(0, older.count("initialized"))
        assertEquals(0, newer.count("initialized"))
        assertNull(PluginCatalog(catalogDirectory).bootstrapPackage("missing.probe"))
    }

    @Test
    fun `bootstrap preserves an actionable error when every requested candidate is unavailable`(): Unit = runBlocking {
        val broken = fixture(version = "2.0.0")
        val incompatible = fixture(version = "1.0.0", apiVersion = 2)
        publish(incompatible, broken)
        Files.write(catalogDirectory.resolve(broken.fileName), broken.archive + 0)

        val error = failure("PLUGIN_INCOMPATIBLE") {
            PluginCatalog(catalogDirectory).bootstrapPackage(broken.manifest.id)
        }
        assertTrue(error.message.orEmpty().contains("PLUGIN_HASH_MISMATCH"))
        assertEquals(0, broken.count("initialized"))
        assertEquals(0, incompatible.count("initialized"))
    }

    @Test
    fun `semantic prerelease comparison treats signed-looking identifiers as ASCII alphanumeric`() {
        assertTrue(PluginCatalog.compareVersions("1.0.0--1", "1.0.0--2") < 0)
        assertTrue(PluginCatalog.compareVersions("1.0.0-9", "1.0.0--1") < 0)
    }

    @Test
    fun `entry action and field identifiers keep their existing 64 character bounds`(): Unit = runBlocking {
        val packaged = fixture()
        val entry = packaged.ui.entries.first()
        val identifier = "a".repeat(64)
        val bounded = entry.copy(
            id = identifier,
            action = entry.action.copy(
                id = identifier,
                fields = entry.action.fields + PluginField(identifier, "Field", "text"),
            ),
        )
        assertEquals(null, PluginValidation.descriptors(packaged.manifest, packaged.ui.copy(entries = listOf(bounded))))
        val invalid = listOf(
            bounded.copy(id = identifier + "a"),
            bounded.copy(action = bounded.action.copy(id = identifier + "a")),
            bounded.copy(action = bounded.action.copy(fields = bounded.action.fields.map { if (it.key == identifier) it.copy(key = identifier + "a") else it })),
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
    fun `scope choices must be executable in every declared context`(): Unit = runBlocking {
        val packaged = fixture()
        val channel = packaged.ui.entries.first()
        val oauth = packaged.ui.entries.last()

        fun entryWithScope(
            entry: PluginUiEntry,
            choices: List<String>?,
            required: Boolean = true,
            supportsBatch: Boolean = entry.action.supportsBatch,
            maxLength: Int? = null,
            defaultValue: JsonPrimitive? = null,
        ) = entry.copy(action = entry.action.copy(
            supportsBatch = supportsBatch,
            fields = entry.action.fields.filterNot { it.kind == "scope" } + if (choices == null) emptyList() else listOf(
                PluginField("scope", "Scope", "scope", required = required, defaultValue = defaultValue, maxLength = maxLength, options = choices.map { PluginChoice(it, it) }),
            ),
        ))

        fun validate(entry: PluginUiEntry) = PluginValidation.descriptors(
            packaged.manifest, packaged.ui.copy(entries = listOf(entry)),
        )

        failure("PLUGIN_INVALID_PACKAGE") { validate(entryWithScope(oauth, listOf("all"))) }
        assertEquals(null, validate(entryWithScope(oauth, listOf("single"))))
        failure("PLUGIN_INVALID_PACKAGE") { validate(entryWithScope(oauth, listOf("single"), maxLength = 1)) }
        failure("PLUGIN_INVALID_PACKAGE") { validate(entryWithScope(oauth, listOf("single"), defaultValue = JsonPrimitive("all"))) }
        assertEquals(null, validate(entryWithScope(oauth, listOf("single"), defaultValue = JsonPrimitive("single"))))

        failure("PLUGIN_INVALID_PACKAGE") { validate(entryWithScope(channel, listOf("all"), supportsBatch = false)) }
        failure("PLUGIN_INVALID_PACKAGE") { validate(entryWithScope(channel, listOf("all"))) }
        failure("PLUGIN_INVALID_PACKAGE") { validate(entryWithScope(channel, listOf("all"), required = false)) }
        assertEquals(null, validate(entryWithScope(channel, listOf("single"))))
        assertEquals(null, validate(entryWithScope(channel, listOf("single", "all"))))
        assertEquals(null, validate(entryWithScope(channel, null)))

        val shared = entryWithScope(
            channel.copy(placement = "plugin-page", contexts = listOf("channel", "oauth-account")), listOf("single", "all"),
        )
        assertEquals(null, validate(shared))
        failure("PLUGIN_INVALID_PACKAGE") {
            validate(entryWithScope(channel.copy(contexts = listOf("channel", "oauth-account")), listOf("all")))
        }
    }

    @Test
    fun `static choices obey their own field limits without requiring defaults`(): Unit = runBlocking {
        val packaged = fixture()
        val entry = packaged.ui.entries.first()
        val choices = listOf(
            Triple("mode", "select", "enabled"),
            Triple("model", "model", "vendor/model"),
            Triple("keyId", "credential", "opaque/key"),
            Triple("protocol", "protocol", "custom.protocol"),
            Triple("scope", "scope", "single"),
        )
        for ((key, kind, value) in choices) {
            for (required in if (kind in setOf("model", "protocol")) listOf(true) else listOf(false, true)) {
                val field = PluginField(
                    key, "Choice", kind, required = required, maxLength = value.length,
                    options = listOf(PluginChoice(value, "Choice")),
                )
                assertNull(field.defaultValue)
                val manifest = packaged.manifest.copy(configuration = listOf(field))
                val ui = packaged.ui.copy(entries = listOf(entry.copy(action = entry.action.copy(
                    fields = entry.action.fields.filterNot { it.key == key } + field,
                ))))
                assertNull(PluginValidation.descriptors(manifest, ui))
                assertEquals(JsonPrimitive(value), PluginValidation.configuration(
                    manifest.configuration, JsonObject(mapOf(key to JsonPrimitive(value))),
                )[key])

                for (maximum in listOf(1, value.length - 1)) {
                    val invalid = field.copy(maxLength = maximum)
                    failure("PLUGIN_INVALID_PACKAGE") {
                        PluginValidation.descriptors(manifest.copy(configuration = listOf(invalid)), packaged.ui)
                    }
                    failure("PLUGIN_INVALID_PACKAGE") {
                        PluginValidation.descriptors(packaged.manifest, ui.copy(entries = listOf(entry.copy(action = entry.action.copy(
                            fields = entry.action.fields.filterNot { it.key == key } + invalid,
                        )))))
                    }
                }
                if (kind == "select") {
                    val mixed = field.copy(options = field.options + PluginChoice(value + "x", "Too long"))
                    failure("PLUGIN_INVALID_PACKAGE") {
                        PluginValidation.descriptors(manifest.copy(configuration = listOf(mixed)), packaged.ui)
                    }
                    failure("PLUGIN_INVALID_PACKAGE") {
                        PluginValidation.descriptors(packaged.manifest, ui.copy(entries = listOf(entry.copy(action = entry.action.copy(
                            fields = entry.action.fields + mixed,
                        )))))
                    }
                }
            }
        }
        for (maximum in listOf(7, 1)) {
            val select = PluginField(
                "mode", "Mode", "select", required = true, maxLength = maximum,
                options = listOf(PluginChoice("enabled", "Enabled")),
            )
            val documents = listOf(
                "manifest.json" to PluginValidation.json.encodeToString(packaged.manifest.copy(configuration = listOf(select))),
                "ui.json" to PluginValidation.json.encodeToString(packaged.ui.copy(entries = listOf(entry.copy(action = entry.action.copy(
                    fields = entry.action.fields + select,
                ))))),
            )
            for ((name, document) in documents) {
                publish(packaged.repack(packaged.entries.map { if (it.name == name) it.copy(bytes = document.toByteArray()) else it }))
                if (maximum == 7) assertTrue(PluginCatalog(catalogDirectory).items().single().compatible)
                else rejectedPackage("PLUGIN_INVALID_PACKAGE")
            }
        }
        assertEquals(0, packaged.count("initialized"))
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
    fun `archive bounds prefixes trailers metadata and central directory are checked before ZIP parsing`() : Unit = runBlocking {
        val packaged = fixture()
        val oversizedCentralSize = packaged.archive.copyOf().also { archive ->
            val end = archive.size - 22
            archive[end + 12] = 0xff.toByte()
            archive[end + 13] = 0xff.toByte()
            archive[end + 14] = 0xff.toByte()
            archive[end + 15] = 0x7f
        }
        val zip64SentinelCount = packaged.archive.copyOf().also { archive ->
            val end = archive.size - 22
            archive[end + 10] = 0xff.toByte()
            archive[end + 11] = 0xff.toByte()
        }
        val centralEntrySentinel = packaged.archive.copyOf().also { archive ->
            val central = little32ForTest(archive, archive.size - 6).toInt()
            archive[central + 20] = 0xff.toByte()
            archive[central + 21] = 0xff.toByte()
            archive[central + 22] = 0xff.toByte()
            archive[central + 23] = 0xff.toByte()
        }
        val mismatchedEntryCount = packaged.archive.copyOf().also { archive ->
            val end = archive.size - 22
            archive[end + 8] = 2
            archive[end + 9] = 0
            archive[end + 10] = 2
            archive[end + 11] = 0
        }
        val splitEntry = packaged.archive.copyOf().also { archive ->
            val central = little32ForTest(archive, archive.size - 6).toInt()
            archive[central + 34] = 1
        }
        val unsupportedMethod = packaged.archive.copyOf().also { archive ->
            val central = little32ForTest(archive, archive.size - 6).toInt()
            archive[central + 10] = 99
        }
        val archives = listOf(
            packaged.archive + "trailer".toByteArray(),
            "#!/bin/sh\\n".toByteArray() + packaged.archive,
            zipFixture(packaged.entries.map { if (it.name == "manifest.json") it.copy(bytes = ByteArray(PluginValidation.MAX_METADATA_BYTES + 1)) else it }),
            zipFixture(packaged.entries + ZipFixtureEntry("extra", byteArrayOf(1))),
            oversizedCentralSize,
            zip64SentinelCount,
            centralEntrySentinel,
            mismatchedEntryCount,
            splitEntry,
            unsupportedMethod,
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
            entry.action.copy(fields = entry.action.fields.map { if (it.key == "prompt") it.copy(kind = "javascript") else it }),
            entry.action.copy(fields = entry.action.fields + PluginField("endpoint", "Endpoint", "text")),
            entry.action.copy(fields = entry.action.fields + PluginField("chosenModel", "Model", "model")),
            entry.action.copy(fields = entry.action.fields + PluginField("chosenCredential", "Credential", "credential")),
            entry.action.copy(fields = entry.action.fields + PluginField("chosenProtocol", "Protocol", "protocol")),
            entry.action.copy(fields = entry.action.fields + PluginField("chosenScope", "Scope", "scope", options = listOf(PluginChoice("single", "Single")))),
            entry.action.copy(fields = entry.action.fields + PluginField("flag", "Flag", "boolean", defaultValue = JsonPrimitive("true"))),
            entry.action.copy(fields = entry.action.fields + PluginField("count", "Count", "number", min = 2.0, max = 1.0)),
            entry.action.copy(fields = entry.action.fields.filterNot { it.key == "model" }),
            entry.action.copy(fields = entry.action.fields.map { if (it.key == "model") it.copy(required = false) else it }),
            entry.action.copy(fields = entry.action.fields.map { if (it.key == "model") it.copy(kind = "text") else it }),
            entry.action.copy(fields = entry.action.fields.filterNot { it.key == "protocol" }),
            entry.action.copy(fields = entry.action.fields.map { if (it.key == "protocol") it.copy(required = false) else it }),
            entry.action.copy(fields = entry.action.fields.map { if (it.key == "protocol") it.copy(kind = "text") else it }),
            entry.action.copy(fields = entry.action.fields + List(33 - entry.action.fields.size) { PluginField("field$it", "Field", "text") }),
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
            original.map { if (it.name == "META-INF/MANIFEST.MF") it.copy(name = "meta-inf/manifest.mf") else it },
            original.map { if (it.name == "META-INF/MANIFEST.MF") it.copy(bytes = "Manifest-Version: 1.0\r\nClass-Path: other.jar\r\n\r\n".toByteArray()) else it },
            original + ZipFixtureEntry("meta-inf/manifest.mf", "Manifest-Version: 1.0\r\n\r\n".toByteArray()),
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
        try {
            assertFalse(PosixFilePermission.OWNER_WRITE in Files.getPosixFilePermissions(staged.jarPath))
        } catch (_: UnsupportedOperationException) {
            // Keep the archive, idempotence, and no-load checks on non-POSIX file systems.
        }
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
