package dev.soven.prismllm.config

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Tag("unit")
class AntigravityAgyConfigTest {
    private val env = mapOf(
        "ANTIGRAVITY_AGY_ENABLED" to "true",
        "ANTIGRAVITY_AGY_SCRIPT" to "/opt/prism/scripts/antigravity_agy_admin.py",
        "ANTIGRAVITY_AGY_STATE_DIR" to "/var/lib/prism/agy-accounts",
    )

    @Test
    fun disabledUnlessExplicitlyConfigured() {
        assertNull(AntigravityAgyConfig.fromEnv(emptyMap()))
        assertNull(AntigravityAgyConfig.fromEnv(mapOf("ANTIGRAVITY_AGY_ENABLED" to "false")))
        assertNotNull(AntigravityAgyConfig.fromEnv(env))
        assertFailsWith<IllegalStateException> { AntigravityAgyConfig.fromEnv(env + ("ANTIGRAVITY_AGY_ENABLED" to "yes")) }
    }

    @Test
    fun commandsUseDedicatedDirectoryAndNeverPassTokens() {
        val config = requireNotNull(AntigravityAgyConfig.fromEnv(env))
        val state = Path.of("/var/lib/prism/agy-accounts/fixture")
        assertEquals(listOf("/usr/bin/python3", "/opt/prism/scripts/antigravity_agy_admin.py", "--state", state.toString(),
            "--proxy", "http://127.0.0.1:7897"), config.loginCommand(state))
        assertEquals(listOf("agent", "--online", "--", "acp"), config.inspectCommand(state).takeLast(4))
    }

    @Test
    fun rejectsRelativePathsAndNonLoopbackOrCredentialedProxies() {
        assertFailsWith<IllegalArgumentException> {
            AntigravityAgyConfig.fromEnv(env + ("ANTIGRAVITY_AGY_STATE_DIR" to "relative"))
        }
        listOf("http://example.invalid:7897", "http://user:password@127.0.0.1:7897", "http://127.0.0.1:70000",
            "http://127.0.0.1:7897/path").forEach { proxy ->
            assertFailsWith<IllegalArgumentException> { AntigravityAgyConfig.fromEnv(env + ("ANTIGRAVITY_AGY_PROXY" to proxy)) }
        }
    }
}
