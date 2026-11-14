package dev.soven.prismllm.plugin.iq

import dev.soven.prismllm.spi.iq.IqJudge
import dev.soven.prismllm.spi.iq.IqJudgeRequest
import dev.soven.prismllm.spi.iq.IqJudgeResult
import dev.soven.prismllm.spi.iq.IqJudgeVerdict
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.*

@Tag("component")
class IqJudgeRegistryTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `registers valid judges and closes rejected instances`() {
        val valid = TestJudge("alpha")
        val rejected = listOf(
            TestJudge("alpha"),
            TestJudge("INVALID"),
            TestJudge("old", apiVersion = 99),
            TestJudge(" spaced "),
        )
        IqJudgeRegistry(builtIns = listOf(valid) + rejected).use { registry ->
            assertEquals(listOf("alpha"), registry.judgeInfos.map { it.id })
            assertEquals(4, registry.loadFailures.size)
            assertSame(valid, registry.find("alpha"))
            assertNull(registry.find("missing"))
            assertTrue(rejected.all { it.closed })
            assertFalse(valid.closed)
        }
        assertTrue(valid.closed)
    }

    @Test
    fun `loads external judge jar`() {
        val classes = directory.resolve("classes")
        Files.createDirectories(classes)
        val java = directory.resolve("ExternalJudge.java")
        Files.writeString(
            java,
            """
            package external;
            import dev.soven.prismllm.spi.iq.*;
            import java.util.Collections;
            public final class ExternalJudge implements IqJudge {
                public String getId() { return "external"; }
                public String getDisplayName() { return "External"; }
                public String getDescription() { return "External judge"; }
                public int getApiVersion() { return 1; }
                public IqJudgeResult judge(IqJudgeRequest request) {
                    return new IqJudgeResult(IqJudgeVerdict.REVIEW, "review", null, null, Collections.emptyMap());
                }
                public void close() {}
            }
            """.trimIndent(),
        )
        val compiler = ToolProvider.getSystemJavaCompiler()
        assertEquals(
            0,
            compiler.run(
                null, null, null,
                "-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString(), java.toString(),
            ),
        )
        writeJar("01-invalid.jar", "invalid judge\n")
        writeJar("02-missing.jar", "external.MissingJudge\n")
        writeJar("03-valid.jar", "external.ExternalJudge\n", Files.readAllBytes(classes.resolve("external/ExternalJudge.class")))

        IqJudgeRegistry(pluginDirectory = directory).use { registry ->
            val judge = assertNotNull(registry.find("external"))
            assertNotSame(javaClass.classLoader, judge.javaClass.classLoader)
            assertEquals(2, registry.loadFailures.size)
            assertEquals("External", registry.judgeInfos.single { it.id == "external" }.displayName)
            assertEquals("External judge", registry.judgeInfos.single { it.id == "external" }.description)
            assertEquals(IqJudgeVerdict.REVIEW, judge.judge(sampleRequest()).verdict)
        }
    }

    @Test
    fun `missing directory is empty and non-directory records failure`() {
        IqJudgeRegistry(pluginDirectory = directory.resolve("missing")).use {
            assertTrue(it.judges.isEmpty())
            assertTrue(it.loadFailures.isEmpty())
        }
        val file = Files.writeString(directory.resolve("file"), "not a directory")
        IqJudgeRegistry(pluginDirectory = file).use {
            assertTrue(it.judges.isEmpty())
            assertEquals(1, it.loadFailures.size)
        }
    }

    private fun sampleRequest() = IqJudgeRequest(
        benchmark = "pelican",
        html = "<html><svg/></html>",
        model = "test",
        structure = dev.soven.prismllm.proxy.analyzePelicanStructure("<html><svg/></html>"),
    )

    private fun writeJar(name: String, descriptor: String, bytecode: ByteArray? = null) {
        JarOutputStream(Files.newOutputStream(directory.resolve(name))).use { jar ->
            if (bytecode != null) {
                jar.putNextEntry(JarEntry("external/ExternalJudge.class"))
                jar.write(bytecode)
                jar.closeEntry()
            }
            jar.putNextEntry(JarEntry("META-INF/services/" + IqJudge::class.java.name))
            jar.write(descriptor.toByteArray())
            jar.closeEntry()
        }
    }

    private class TestJudge(
        override val id: String,
        override val apiVersion: Int = 1,
    ) : IqJudge {
        var closed = false

        override fun judge(request: IqJudgeRequest) = IqJudgeResult(IqJudgeVerdict.REVIEW, "review")

        override fun close() {
            closed = true
        }
    }
}
