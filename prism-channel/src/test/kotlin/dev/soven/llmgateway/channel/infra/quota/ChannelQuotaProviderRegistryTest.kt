package dev.soven.llmgateway.channel.infra.quota

import dev.soven.llmgateway.spi.quota.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.*

class ChannelQuotaProviderRegistryTest {
    @TempDir lateinit var directory: Path

    @Test
    fun registersValidProvidersAndClosesRejectedInstances() {
        val valid = TestProvider("alpha")
        val rejected = listOf(TestProvider("alpha"), TestProvider("INVALID"),
            TestProvider("old", apiVersion = 99), TestProvider(" spaced "))
        ChannelQuotaProviderRegistry(builtIns = listOf(valid) + rejected).use { registry ->
            assertEquals(listOf("alpha"), registry.providerInfos.map { it.id })
            assertEquals(4, registry.loadFailures.size)
            assertSame(valid, registry.find("alpha"))
            assertNull(registry.find("missing"))
            assertTrue(rejected.all { it.closed })
            assertFalse(valid.closed)
        }
        assertTrue(valid.closed)
    }

    @Test
    fun loadsExternalClassEvenAfterBrokenServiceDeclarations() {
        val classes = directory.resolve("classes")
        Files.createDirectories(classes)
        val java = directory.resolve("ExternalProvider.java")
        Files.writeString(java, """
            package external;
            import dev.soven.llmgateway.spi.quota.*;
            import java.math.BigDecimal;
            public final class ExternalProvider implements ChannelQuotaProvider {
                public String getId() { return "external"; }
                public String getDisplayName() { return "External"; }
                public int getApiVersion() { return 1; }
                public ChannelQuotaResult query(ChannelQuotaQuery q) {
                    return new ChannelQuotaResult(ChannelQuotaStatus.AVAILABLE,
                        new BigDecimal("1.25"), null, null, "USD", null, null, 123L);
                }
                public void close() {}
            }
        """.trimIndent())
        val compiler = ToolProvider.getSystemJavaCompiler()
        assertEquals(0, compiler.run(null, null, null, "-classpath", System.getProperty("java.class.path"),
            "-d", classes.toString(), java.toString()))
        writeJar("01-invalid.jar", "invalid provider name\n")
        writeJar("02-missing.jar", "external.MissingProvider\n")
        writeJar("03-valid.jar", "external.ExternalProvider\n",
            Files.readAllBytes(classes.resolve("external/ExternalProvider.class")))

        ChannelQuotaProviderRegistry(pluginDirectory = directory).use { registry ->
            val provider = assertNotNull(registry.find("external"))
            assertNotSame(javaClass.classLoader, provider.javaClass.classLoader)
            assertEquals(2, registry.loadFailures.size)
            assertEquals("External", registry.providerInfos.single().displayName)
            assertEquals("1.25", provider.query(ChannelQuotaQuery("c", "https://upstream.example/v1",
                "bearer", "secret")).remaining!!.toPlainString())
        }
    }

    @Test
    fun missingDirectoryIsEmptyAndNonDirectoryRecordsFailure() {
        ChannelQuotaProviderRegistry(pluginDirectory = directory.resolve("missing")).use {
            assertTrue(it.providers.isEmpty())
            assertTrue(it.loadFailures.isEmpty())
        }
        val file = Files.writeString(directory.resolve("file"), "not a directory")
        ChannelQuotaProviderRegistry(pluginDirectory = file).use {
            assertTrue(it.providers.isEmpty())
            assertEquals(1, it.loadFailures.size)
        }
    }

    private fun writeJar(name: String, descriptor: String, bytecode: ByteArray? = null) {
        JarOutputStream(Files.newOutputStream(directory.resolve(name))).use { jar ->
            if (bytecode != null) {
                jar.putNextEntry(JarEntry("external/ExternalProvider.class"))
                jar.write(bytecode)
                jar.closeEntry()
            }
            jar.putNextEntry(JarEntry("META-INF/services/" + ChannelQuotaProvider::class.java.name))
            jar.write(descriptor.toByteArray())
            jar.closeEntry()
        }
    }

    private class TestProvider(override val id: String, override val apiVersion: Int = 1) : ChannelQuotaProvider {
        var closed = false
        override fun query(request: ChannelQuotaQuery) = ChannelQuotaResult(ChannelQuotaStatus.UNSUPPORTED)
        override fun close() { closed = true }
    }
}
