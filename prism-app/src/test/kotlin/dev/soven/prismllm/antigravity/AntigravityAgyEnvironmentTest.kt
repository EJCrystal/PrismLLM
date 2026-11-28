package dev.soven.prismllm.antigravity

import dev.soven.prismllm.config.AntigravityAgyConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("unit")
class AntigravityAgyEnvironmentTest {
    @TempDir
    lateinit var directory: Path

    private fun config(): AntigravityAgyConfig {
        val scripts = Files.createDirectories(directory.resolve("scripts"))
        listOf("antigravity_agy_admin.py", "antigravity_acp_pilot.py", "antigravity-acp-release.json").forEach {
            Files.writeString(scripts.resolve(it), "fixture")
        }
        return AntigravityAgyConfig(scripts.resolve("antigravity_agy_admin.py"), directory.resolve("accounts"))
    }

    @Test
    fun installedDependenciesAreCheckedButNeverReinstalled() = runBlocking {
        val commands = mutableListOf<List<String>>()
        val phases = mutableListOf<String>()
        val environment = AntigravityAgyEnvironment(executable = { true }, command = { command, _, _ ->
            commands.add(command)
            if ("--version" in command) "Python 3.12.8" else ""
        })
        environment.prepare(config(), { phase, _ -> phases.add(phase) }, { true })
        assertEquals(listOf("CHECKING", "PREPARING"), phases)
        assertFalse(commands.any { it.first().endsWith("apt-get") })
        assertEquals("--prepare", commands.last().last())
    }

    @Test
    fun onlyMissingPackagesAreInstalledAndThenRechecked() = runBlocking {
        var pythonAvailable = false
        val commands = mutableListOf<List<String>>()
        var reported = emptyList<String>()
        val environment = AntigravityAgyEnvironment(
            executable = { it.toString() != "/usr/bin/python3" || pythonAvailable },
            command = { command, _, _ ->
                commands.add(command)
                when {
                    command.first() == "/usr/bin/id" -> "0"
                    "--version" in command -> "Python 3.12.8"
                    "install" in command -> { pythonAvailable = true; "" }
                    else -> ""
                }
            },
        )
        environment.prepare(config(), { phase, missing -> if (phase == "INSTALLING") reported = missing }, { true })
        assertEquals(listOf("Python 3.11+"), reported)
        assertEquals(listOf("/usr/bin/apt-get", "install", "-y", "--no-install-recommends", "python3"),
            commands.first { "install" in it })
        assertEquals("--prepare", commands.last().last())
    }

    @Test
    fun missingDependenciesAndLackOfPermissionAreReportedWithoutSudoOrDownload() = runBlocking {
        val commands = mutableListOf<List<String>>()
        var missing = emptyList<String>()
        val environment = AntigravityAgyEnvironment(
            executable = { it.toString() == "/usr/bin/apt-get" },
            command = { command, _, _ -> commands.add(command); "1000" },
        )
        val failure = assertFailsWith<AgyPreparationException> {
            environment.prepare(config(), { _, dependencies -> if (dependencies.isNotEmpty()) missing = dependencies }, { true })
        }
        assertEquals(listOf("Python 3.11+", "Bubblewrap"), missing)
        assertTrue(failure.message.orEmpty().contains("没有系统安装权限"))
        assertEquals(listOf(listOf("/usr/bin/id", "-u")), commands)
    }

    @Test
    fun disablingAfterPackageIndexDownloadDoesNotInstallOrStartCli() = runBlocking {
        var enabled = true
        val commands = mutableListOf<List<String>>()
        val environment = AntigravityAgyEnvironment(
            executable = { it.toString() != "/usr/bin/python3" },
            command = { command, _, _ ->
                commands.add(command)
                if ("update" in command) enabled = false
                if (command.first() == "/usr/bin/id") "0" else ""
            },
        )
        environment.prepare(config(), { _, _ -> }, { enabled })
        assertFalse(commands.any { "install" in it || "--prepare" in it })
    }
}
