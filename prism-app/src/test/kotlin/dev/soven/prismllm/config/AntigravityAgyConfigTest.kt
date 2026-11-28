package dev.soven.prismllm.config

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Tag("unit")
class AntigravityAgyConfigTest {
    private val config = AntigravityAgyConfig(
        script = Path.of("/opt/prism/scripts/antigravity_agy_admin.py"),
        stateDir = Path.of("/var/lib/prism/agy-accounts"),
    )

    @Test
    fun defaultsUseApplicationManagedPaths() {
        val defaults = AntigravityAgyConfig.defaults()
        assertEquals(Path.of("scripts/antigravity_agy_admin.py").toAbsolutePath().normalize(), defaults.script)
        assertEquals(Path.of("data/antigravity-agy").toAbsolutePath().normalize(), defaults.stateDir)
        assertEquals(Path.of("/usr/bin/python3"), defaults.python)
        assertEquals("http://127.0.0.1:7897", defaults.proxy)
    }

    @Test
    fun commandsUseDedicatedDirectoryAndNeverPassTokens() {
        val state = Path.of("/var/lib/prism/agy-accounts/fixture")
        assertEquals(listOf("/usr/bin/python3", "/opt/prism/scripts/antigravity_agy_admin.py", "--state", state.toString(),
            "--proxy", "http://127.0.0.1:7897"), config.loginCommand(state))
        assertEquals(listOf("agent", "--online", "--", "acp"), config.inspectCommand(state).takeLast(4))
    }

    @Test
    fun rejectsRelativePathsAndNonLoopbackOrCredentialedProxies() {
        assertFailsWith<IllegalArgumentException> {
            config.copy(stateDir = Path.of("relative"))
        }
        listOf("http://example.invalid:7897", "http://user:password@127.0.0.1:7897", "http://127.0.0.1:70000",
            "http://127.0.0.1:7897/path").forEach { proxy ->
            assertFailsWith<IllegalArgumentException> { config.copy(proxy = proxy) }
        }
    }
}
