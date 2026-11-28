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
        assertEquals("", defaults.proxy)
    }

    @Test
    fun commandsUseDedicatedDirectoryAndNeverPassTokens() {
        val state = Path.of("/var/lib/prism/agy-accounts/fixture")
        assertEquals(listOf("/usr/bin/python3", "/opt/prism/scripts/antigravity_agy_admin.py", "--state", state.toString(),
            "--proxy", ""), config.loginCommand(state))
        assertEquals(listOf("agent", "--online", "--", "acp"), config.inspectCommand(state).takeLast(4))
        val authenticated = config.copy(proxy = "http://proxy.example:8080", proxyUsername = "agy-user", proxyPassword = "agy-secret")
        assertEquals(mapOf("AGY_PROXY_USERNAME" to "agy-user", "AGY_PROXY_PASSWORD" to "agy-secret"), authenticated.proxyEnvironment())
        assertEquals(false, authenticated.loginCommand(state).contains("agy-secret"))
    }

    @Test
    fun allowsDirectAndRemoteHttpProxyWithSeparateAuthenticationButRejectsMalformedEndpoints() {
        assertFailsWith<IllegalArgumentException> {
            config.copy(stateDir = Path.of("relative"))
        }
        assertEquals("", config.proxy)
        assertEquals("http://proxy.example:7897", config.copy(proxy = "http://proxy.example:7897").proxy)
        listOf("ftp://proxy.example:7897", "http://user:password@proxy.example:7897", "http://127.0.0.1:0",
            "http://127.0.0.1:70000", "http://127.0.0.1:7897/path").forEach { proxy ->
            assertFailsWith<IllegalArgumentException> { config.copy(proxy = proxy) }
        }
        assertFailsWith<IllegalArgumentException> { config.copy(proxyUsername = "agy-user") }
        assertFailsWith<IllegalArgumentException> { config.copy(proxy = "http://proxy.example", proxyUsername = "agy-user", proxyPassword = "") }
    }
}
