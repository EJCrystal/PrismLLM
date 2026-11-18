package dev.soven.prismllm.plugin.host

import dev.soven.prismllm.plugin.sdk.DiagnosticPlugin
import dev.soven.prismllm.plugin.sdk.PluginManifest
import dev.soven.prismllm.plugin.sdk.PluginUiDescriptor
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest
import java.util.jar.Manifest
import java.util.zip.CRC32

internal data class ValidatedPluginPackage(
    val item: PluginCatalogItem,
    val archive: ByteArray,
    val jar: ByteArray,
    val providerClass: String,
)

internal object PluginPackages {
    const val MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
    const val MAX_JAR_BYTES = 32 * 1024 * 1024
    const val SERVICE_PATH = "META-INF/services/dev.soven.prismllm.plugin.sdk.DiagnosticPlugin"
    private const val MAX_JAR_EXPANDED_BYTES = 64L * 1024 * 1024
    private const val MAX_RATIO = 200L
    private val packageFiles = setOf("manifest.json", "ui.json", "plugin.jar")

    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun validate(archive: ByteArray, id: String, version: String, sha256: String, source: String): ValidatedPluginPackage {
        PluginValidation.identity(id, version)
        val expectedHash = PluginValidation.digest(sha256)
        if (hash(archive) != expectedHash) throw PluginHostException("PLUGIN_HASH_MISMATCH", "Package does not match its trusted SHA-256")
        PluginValidation.requireData(archive.size <= MAX_ARCHIVE_BYTES, "Package archive is too large")
        val files = LinkedHashMap<String, ByteArray>()
        readZip(archive, 3, MAX_JAR_BYTES.toLong() + 2 * PluginValidation.MAX_METADATA_BYTES) { zip, entry ->
            PluginValidation.requireData(!entry.isDirectory && entry.name in packageFiles, "Package must contain only manifest.json, ui.json and plugin.jar")
            val limit = if (entry.name == "plugin.jar") MAX_JAR_BYTES else PluginValidation.MAX_METADATA_BYTES
            files[entry.name] = entryBytes(zip, entry, limit)
        }
        PluginValidation.requireData(files.keys == packageFiles, "Package must contain manifest.json, ui.json and plugin.jar")
        val manifest = PluginValidation.decode<PluginManifest>(files.getValue("manifest.json"), "manifest")
        val ui = PluginValidation.decode<PluginUiDescriptor>(files.getValue("ui.json"), "UI descriptor")
        PluginValidation.requireData(manifest.id == id && manifest.version == version, "Package identity does not match the catalogue or installation")
        val incompatibility = PluginValidation.descriptors(manifest, ui)
        val jar = files.getValue("plugin.jar")
        val provider = validateJar(jar)
        return ValidatedPluginPackage(
            PluginCatalogItem(manifest, ui, expectedHash, source, incompatibility == null, incompatibility), archive, jar, provider,
        )
    }

    private fun validateJar(bytes: ByteArray): String {
        val names = HashSet<String>()
        var service: ByteArray? = null
        readZip(bytes, 10_000, MAX_JAR_EXPANDED_BYTES) { zip, entry ->
            val name = entry.name
            names.add(name)
            val classPath = name.replaceFirst(Regex("^META-INF/versions/[0-9]+/"), "")
            PluginValidation.requireData(!classPath.startsWith("dev/soven/prismllm/plugin/sdk/") && !classPath.startsWith("dev/soven/prismllm/plugin/host/"), "Plugin JAR must not bundle SDK or host classes")
            PluginValidation.requireData(name != "META-INF/INDEX.LIST", "Plugin JAR indexes may not reference external JARs")
            val value = entryBytes(zip, entry, if (name == SERVICE_PATH || name == "META-INF/MANIFEST.MF") PluginValidation.MAX_METADATA_BYTES else MAX_JAR_BYTES)
            when (name) {
                SERVICE_PATH -> service = value
                "META-INF/MANIFEST.MF" -> {
                    val manifest = Manifest(ByteArrayInputStream(value))
                    PluginValidation.requireData(manifest.mainAttributes.getValue("Class-Path").isNullOrBlank(), "Plugin JAR may not declare an external Class-Path")
                }
            }
        }
        val descriptor = service ?: PluginValidation.invalid("Plugin JAR is missing the DiagnosticPlugin service descriptor")
        val text = descriptor.toString(Charsets.UTF_8)
        PluginValidation.requireData(text.toByteArray(Charsets.UTF_8).contentEquals(descriptor), "Service descriptor must be UTF-8")
        val providers = text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toList()
        PluginValidation.requireData(providers.size == 1, "Plugin JAR must declare exactly one DiagnosticPlugin provider")
        val provider = providers.single()
        PluginValidation.requireData(Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+").matches(provider), "Invalid DiagnosticPlugin provider class")
        PluginValidation.requireData(provider.replace('.', '/') + ".class" in names, "DiagnosticPlugin provider must be contained in plugin.jar")
        return provider
    }

    private fun readZip(bytes: ByteArray, maxEntries: Int, maxExpanded: Long, consume: (ZipFile, ZipArchiveEntry) -> Unit) {
        try {
            PluginValidation.requireData(bytes.size >= 22 && little32(bytes, 0) == 0x04034b50L, "Archive must be a ZIP without an executable prefix")
            checkEndOfZip(bytes)
            ZipFile.builder().setSeekableByteChannel(SeekableInMemoryByteChannel(bytes)).setUseUnicodeExtraFields(false).get().use { zip ->
                val names = HashSet<String>()
                val ranges = ArrayList<Pair<Long, Long>>()
                var total = 0L
                for (entry in zip.entries.asSequence()) {
                    PluginValidation.requireData(names.size < maxEntries && names.add(entry.name), "Too many or duplicate ZIP entries")
                    val parts = entry.name.removeSuffix("/").split('/')
                    PluginValidation.requireData(entry.name.length <= 512 && !entry.name.startsWith('/') && !entry.name.contains('\\') && !entry.name.contains(':') && !entry.name.contains('\u0000') && parts.all { it.isNotEmpty() && it != "." && it != ".." }, "Unsafe ZIP entry path")
                    val fileType = entry.unixMode and 0xf000
                    PluginValidation.requireData(!entry.isUnixSymlink && (fileType == 0 || fileType == 0x8000 || (entry.isDirectory && fileType == 0x4000)), "ZIP links and special files are not allowed")
                    PluginValidation.requireData(!entry.generalPurposeBit.usesEncryption() && !entry.generalPurposeBit.usesStrongEncryption() && entry.method in setOf(0, 8) && zip.canReadEntryData(entry) && entry.diskNumberStart == 0L, "Encrypted, split or unsupported ZIP entries are not allowed")
                    PluginValidation.requireData(entry.size >= 0 && entry.compressedSize >= 0 && entry.size <= maxExpanded - total, "ZIP expanded size exceeds the limit")
                    PluginValidation.requireData(entry.size <= maxOf(1L, entry.compressedSize) * MAX_RATIO, "ZIP compression ratio exceeds the limit")
                    total += entry.size
                    val start = entry.localHeaderOffset
                    val data = entry.dataOffset
                    PluginValidation.requireData(start >= 0 && start <= bytes.size - 30L && little32(bytes, start.toInt()) == 0x04034b50L, "Invalid ZIP local header")
                    val nameLength = little16(bytes, start.toInt() + 26)
                    val extraLength = little16(bytes, start.toInt() + 28)
                    PluginValidation.requireData(data == start + 30 + nameLength + extraLength && data <= bytes.size && entry.compressedSize <= bytes.size - data, "Invalid ZIP entry extent")
                    PluginValidation.requireData(nameLength == entry.rawName.size && bytes.copyOfRange(start.toInt() + 30, start.toInt() + 30 + nameLength).contentEquals(entry.rawName), "ZIP local and central names differ")
                    PluginValidation.requireData(little16(bytes, start.toInt() + 8) == entry.method, "ZIP local and central methods differ")
                    PluginValidation.requireData(little16(bytes, start.toInt() + 6) == entry.rawFlag, "ZIP local and central flags differ")
                    ranges.add(start to data + entry.compressedSize)
                    consume(zip, entry)
                }
                PluginValidation.requireData(names.isNotEmpty(), "Empty ZIP is not a plugin package")
                ranges.sortBy { it.first }
                PluginValidation.requireData(ranges.zipWithNext().all { (left, right) -> left.second <= right.first }, "Overlapping ZIP entries are not allowed")
            }
        } catch (error: PluginHostException) {
            throw error
        } catch (_: IOException) {
            PluginValidation.invalid("Invalid or unreadable ZIP archive")
        } catch (_: IllegalArgumentException) {
            PluginValidation.invalid("Invalid ZIP archive")
        }
    }

    private fun entryBytes(zip: ZipFile, entry: ZipArchiveEntry, limit: Int): ByteArray {
        PluginValidation.requireData(entry.size <= limit, "ZIP entry exceeds its size limit")
        val bytes = zip.getInputStream(entry).use { boundedBytes(it, limit) }
        val crc = CRC32().apply { update(bytes) }.value
        PluginValidation.requireData(bytes.size.toLong() == entry.size && crc == entry.crc, "ZIP entry size or CRC does not match")
        return bytes
    }

    private fun checkEndOfZip(bytes: ByteArray) {
        val start = maxOf(0, bytes.size - 22 - 65_535)
        val offset = (bytes.size - 22 downTo start).firstOrNull {
            little32(bytes, it) == 0x06054b50L && it + 22 + little16(bytes, it + 20) == bytes.size
        } ?: PluginValidation.invalid("ZIP has no valid end record or contains trailing data")
        PluginValidation.requireData(little16(bytes, offset + 4) == 0 && little16(bytes, offset + 6) == 0, "Split ZIP archives are not allowed")
    }

    private fun little16(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
    private fun little32(bytes: ByteArray, offset: Int): Long = little16(bytes, offset).toLong() or (little16(bytes, offset + 2).toLong() shl 16)

    fun boundedBytes(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 8_192))
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            PluginValidation.requireData(count <= limit - total, "File exceeds its size limit")
            output.write(buffer, 0, count)
            total += count
        }
        return output.toByteArray()
    }

    fun readFile(path: Path, limit: Int): ByteArray {
        ensureNoLinks(path)
        PluginValidation.requireData(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) <= limit, "Package data must be a bounded regular file")
        return Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { boundedBytes(it, limit) }
    }

    /** Reject symlink ancestors as well as the final entry, including managed-directory escapes. */
    fun ensureNoLinks(path: Path) {
        val absolute = path.toAbsolutePath().normalize()
        var current = absolute.root
        for (part in absolute) {
            current = current.resolve(part)
            PluginValidation.requireData(!Files.isSymbolicLink(current), "Symbolic links are not allowed in plugin paths")
        }
    }
}
