package dev.soven.prismllm.plugin.host

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

internal data class StagedPluginPackage(val item: PluginCatalogItem, val jarPath: Path, val providerClass: String)

/** Publish a complete validated snapshot atomically; an id/version is never overwritten. */
class PluginPackageInstaller(managedDirectory: Path) {
    internal val directory: Path = managedDirectory.toAbsolutePath().normalize()

    internal fun stage(validated: ValidatedPluginPackage): StagedPluginPackage = io {
        PluginCatalog.requireCompatible(validated.item)
        val manifest = validated.item.manifest
        PluginValidation.identity(manifest.id, manifest.version)
        val pluginRoot = directory.resolve(manifest.id)
        ensureDirectory(pluginRoot)
        val destination = pluginRoot.resolve(manifest.version)
        PluginPackages.ensureNoLinks(destination)
        if (Files.exists(destination, NOFOLLOW_LINKS)) return@io existing(validated, destination)
        val temporary = pluginRoot.resolve(".${manifest.version}-${UUID.randomUUID()}.staging")
        Files.createDirectory(temporary)
        permissions(temporary, "rwx------")
        try {
            write(temporary.resolve("package.prismplugin"), validated.archive)
            write(temporary.resolve("plugin.jar"), validated.jar)
            permissions(temporary, "r-xr-xr-x")
            try {
                // Same-filesystem directory rename publishes both files together, never partial data.
                Files.move(temporary, destination, ATOMIC_MOVE)
            } catch (_: FileAlreadyExistsException) {
                return@io existing(validated, destination)
            } catch (_: DirectoryNotEmptyException) {
                return@io existing(validated, destination)
            } catch (_: AtomicMoveNotSupportedException) {
                throw PluginHostException("PLUGIN_STAGING_UNAVAILABLE", "Managed plugin storage must support atomic directory publication", 503)
            } catch (error: IOException) {
                // Some providers report an occupied atomic-rename target as a FileSystemException.
                // Validate that winner instead of overwriting it or failing a same-package replay.
                if (Files.exists(destination, NOFOLLOW_LINKS)) return@io existing(validated, destination)
                throw error
            }
            StagedPluginPackage(validated.item, destination.resolve("plugin.jar"), validated.providerClass)
        } finally {
            if (Files.exists(temporary, NOFOLLOW_LINKS)) discardTemporary(temporary)
        }
    }

    internal fun hasVersion(installation: PluginInstallation): Boolean {
        PluginValidation.identity(installation.id, installation.version)
        val path = directory.resolve(installation.id).resolve(installation.version)
        PluginPackages.ensureNoLinks(path)
        return Files.exists(path, NOFOLLOW_LINKS)
    }

    internal fun read(installation: PluginInstallation): StagedPluginPackage = io {
        PluginValidation.identity(installation.id, installation.version)
        PluginValidation.digest(installation.sha256)
        val destination = directory.resolve(installation.id).resolve(installation.version)
        readAt(destination, installation.id, installation.version, installation.sha256, installation.source)
    }

    private fun existing(validated: ValidatedPluginPackage, destination: Path): StagedPluginPackage {
        val archive = PluginPackages.readFile(destination.resolve("package.prismplugin"), PluginPackages.MAX_ARCHIVE_BYTES)
        if (PluginPackages.hash(archive) != validated.item.sha256) {
            throw PluginHostException("PLUGIN_VERSION_CONFLICT", "A different or damaged immutable package already occupies this plugin version", 409)
        }
        return readAt(destination, validated.item.manifest.id, validated.item.manifest.version, validated.item.sha256, validated.item.source, archive)
    }

    private fun readAt(destination: Path, id: String, version: String, sha256: String, source: String, archive: ByteArray? = null): StagedPluginPackage {
        PluginPackages.ensureNoLinks(destination)
        PluginValidation.requireData(Files.isDirectory(destination, NOFOLLOW_LINKS), "Installed package directory is missing")
        Files.list(destination).use { files ->
            PluginValidation.requireData(files.map { it.fileName.toString() }.toList().toSet() == setOf("package.prismplugin", "plugin.jar"), "Installed package directory contains unexpected data")
        }
        val validated = PluginPackages.validate(archive ?: PluginPackages.readFile(destination.resolve("package.prismplugin"), PluginPackages.MAX_ARCHIVE_BYTES), id, version, sha256, source)
        PluginCatalog.requireCompatible(validated.item)
        val jarPath = destination.resolve("plugin.jar")
        PluginValidation.requireData(PluginPackages.readFile(jarPath, PluginPackages.MAX_JAR_BYTES).contentEquals(validated.jar), "Staged JAR does not match the immutable package")
        return StagedPluginPackage(validated.item, jarPath, validated.providerClass)
    }

    private fun ensureDirectory(path: Path) {
        PluginPackages.ensureNoLinks(path)
        Files.createDirectories(path)
        PluginValidation.requireData(Files.isDirectory(path, NOFOLLOW_LINKS), "Managed plugin path must be a directory")
    }

    private fun write(path: Path, bytes: ByteArray) {
        FileChannel.open(path, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        permissions(path, "r--r--r--")
    }

    private fun permissions(path: Path, value: String) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(value))
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX storage still uses create-new files and immutable, validated publication.
        }
    }

    private fun discardTemporary(path: Path) {
        permissions(path, "rwx------")
        Files.list(path).use { files -> files.forEach { Files.delete(it) } }
        Files.delete(path)
    }

    private inline fun <T> io(block: () -> T): T = try {
        block()
    } catch (_: IOException) {
        throw PluginHostException("PLUGIN_STORAGE_UNREADABLE", "Managed plugin storage could not be read or staged", 503)
    }
}
