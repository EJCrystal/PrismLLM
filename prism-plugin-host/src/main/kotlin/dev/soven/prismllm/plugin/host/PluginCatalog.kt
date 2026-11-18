package dev.soven.prismllm.plugin.host

import dev.soven.prismllm.plugin.sdk.PluginManifest
import dev.soven.prismllm.plugin.sdk.PluginUiDescriptor
import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Only deployment-owned, hash-pinned local packages are selectable. No class loading occurs here. */
class PluginCatalog(catalogDirectory: Path) {
    internal val directory: Path = catalogDirectory.toAbsolutePath().normalize()

    fun items(): List<PluginCatalogItem> = io {
        references().map { reference ->
            try {
                io { validate(reference).item }
            } catch (error: PluginHostException) {
                // References are already bounded/validated. Bad package data is display-only and
                // cannot hide unrelated healthy packages or supply executable UI/configuration.
                PluginCatalogItem(
                    manifest = PluginManifest(reference.id, reference.version, reference.id),
                    ui = PluginUiDescriptor(), sha256 = PluginValidation.digest(reference.sha256), source = "bundled",
                    compatible = false, compatibilityReason = "${error.code}: ${error.message}".take(4_000),
                )
            }
        }.sortedWith(compareBy<PluginCatalogItem> { it.manifest.id }.thenComparator { a, b -> compareVersions(b.manifest.version, a.manifest.version) })
    }

    internal fun select(id: String, version: String): ValidatedPluginPackage = io {
        PluginValidation.identity(id, version)
        val reference = references().singleOrNull { it.id == id && it.version == version }
            ?: throw PluginHostException("PLUGIN_PACKAGE_NOT_FOUND", "This plugin version is not in the trusted local catalogue", 404)
        validate(reference).also { requireCompatible(it.item) }
    }

    internal fun bootstrapPackage(id: String): ValidatedPluginPackage? {
        val packages = items().filter { it.manifest.id == id }
        val candidate = packages.filter { it.compatible }.maxWithOrNull { a, b -> compareVersions(a.manifest.version, b.manifest.version) }
        if (candidate == null) {
            // Preserve an actionable bootstrap failure when the optional package exists but none
            // of its versions can be admitted. An absent package remains a normal no-op.
            packages.maxWithOrNull { a, b -> compareVersions(a.manifest.version, b.manifest.version) }?.let { requireCompatible(it) }
            return null
        }
        return select(id, candidate.manifest.version)
    }

    private fun references(): List<CatalogReference> {
        val catalogPath = directory.resolve("catalog.json")
        PluginPackages.ensureNoLinks(directory)
        PluginValidation.requireData(!Files.exists(directory, NOFOLLOW_LINKS) || Files.isDirectory(directory, NOFOLLOW_LINKS), "Catalogue path must be a directory")
        if (!Files.exists(catalogPath, NOFOLLOW_LINKS)) return emptyList()
        val document = PluginValidation.decode<CatalogDocument>(PluginPackages.readFile(catalogPath, PluginValidation.MAX_CATALOG_BYTES), "catalogue")
        PluginValidation.requireData(document.schemaVersion == 1, "Unsupported catalogue schema version")
        PluginValidation.requireData(document.packages.size <= PluginValidation.MAX_PACKAGES, "Too many catalogue packages")
        PluginValidation.requireData(document.packages.map { it.id to it.version }.distinct().size == document.packages.size, "Duplicate catalogue package version")
        PluginValidation.requireData(document.packages.map { it.file }.distinct().size == document.packages.size, "Duplicate catalogue package file")
        document.packages.forEach {
            PluginValidation.identity(it.id, it.version)
            PluginValidation.fileName(it.file)
            PluginValidation.digest(it.sha256)
        }
        return document.packages
    }

    private fun validate(reference: CatalogReference): ValidatedPluginPackage = PluginPackages.validate(
        PluginPackages.readFile(directory.resolve(reference.file), PluginPackages.MAX_ARCHIVE_BYTES),
        reference.id, reference.version, reference.sha256, "bundled",
    )

    private inline fun <T> io(block: () -> T): T = try {
        block()
    } catch (_: IOException) {
        throw PluginHostException("PLUGIN_CATALOG_UNREADABLE", "The trusted local plugin catalogue or package could not be read")
    }

    internal companion object {
        fun requireCompatible(item: PluginCatalogItem) {
            if (!item.compatible) throw PluginHostException("PLUGIN_INCOMPATIBLE", item.compatibilityReason ?: "Unsupported plugin package")
        }

        fun compareVersions(a: String, b: String): Int {
            val left = a.substringBefore('+').split('-', limit = 2)
            val right = b.substringBefore('+').split('-', limit = 2)
            val numbersA = left[0].split('.').map { it.toBigInteger() }
            val numbersB = right[0].split('.').map { it.toBigInteger() }
            for (index in 0..2) {
                val comparison = numbersA[index].compareTo(numbersB[index])
                if (comparison != 0) return comparison
            }
            if (left.size != right.size) return if (left.size == 1) 1 else -1
            if (left.size == 1) return a.compareTo(b)
            val partsA = left[1].split('.')
            val partsB = right[1].split('.')
            for (index in 0 until minOf(partsA.size, partsB.size)) {
                val numberA = partsA[index].toBigIntegerOrNull()
                val numberB = partsB[index].toBigIntegerOrNull()
                val comparison = when {
                    numberA != null && numberB != null -> numberA.compareTo(numberB)
                    numberA != null -> -1
                    numberB != null -> 1
                    else -> partsA[index].compareTo(partsB[index])
                }
                if (comparison != 0) return comparison
            }
            return partsA.size.compareTo(partsB.size).takeIf { it != 0 } ?: a.compareTo(b)
        }
    }
}

@Serializable
private data class CatalogDocument(val schemaVersion: Int, val packages: List<CatalogReference>)

@Serializable
private data class CatalogReference(val id: String, val version: String, val file: String, val sha256: String)
