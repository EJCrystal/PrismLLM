package dev.soven.prismllm.config

import org.junit.jupiter.api.Tag
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("unit")
class AntigravityAcpConfigTest {
    private val script = Path.of("/opt/antigravity/gateway.py")
    private val stateDir = Path.of("/var/lib/antigravity-acp")
    private val firstKeyId = "123e4567-e89b-12d3-a456-426614174000"
    private val secondKeyId = "abcdef01-2345-6789-abcd-0123456789ab"
    private val modelId = "google/gemini-3.0:pro_preview"
    private val requiredEnv = mapOf(
        "ANTIGRAVITY_ACP_ENABLED" to "true",
        "ANTIGRAVITY_ACP_SCRIPT" to script.toString(),
        "ANTIGRAVITY_ACP_STATE_DIR" to stateDir.toString(),
        "ANTIGRAVITY_ACP_MODEL" to modelId,
        "ANTIGRAVITY_ACP_CLIENT_KEY_IDS" to firstKeyId,
    )

    @Test
    fun `ACP is disabled by default even when other settings are present`() {
        assertNull(AntigravityAcpConfig.fromEnv(emptyMap()))
        assertNull(AntigravityAcpConfig.fromEnv(requiredEnv - "ANTIGRAVITY_ACP_ENABLED"))
        assertNull(AntigravityAcpConfig.fromEnv(mapOf("ANTIGRAVITY_ACP_SCRIPT" to "relative.py")))
    }

    @Test
    fun `explicit false disables ACP without validating unused settings`() {
        assertNull(AntigravityAcpConfig.fromEnv(mapOf("ANTIGRAVITY_ACP_ENABLED" to "false")))
        assertNull(AntigravityAcpConfig.fromEnv(requiredEnv + mapOf(
            "ANTIGRAVITY_ACP_ENABLED" to "false",
            "ANTIGRAVITY_ACP_SCRIPT" to "relative.py",
            "ANTIGRAVITY_ACP_CLIENT_KEY_IDS" to "not-a-uuid",
            "ANTIGRAVITY_ACP_TIMEOUT_MS" to "not-a-number",
        )))
    }

    @Test
    fun `enabled flag accepts only strict lowercase booleans`() {
        for (value in listOf(
            "", "TRUE", "FALSE", "True", "False", "1", "0", "yes", " true", "false ", "true\n", "false\r",
        )) {
            val failure = assertFailsWith<IllegalArgumentException> {
                AntigravityAcpConfig.fromEnv(requiredEnv + ("ANTIGRAVITY_ACP_ENABLED" to value))
            }
            assertTrue(failure.message.orEmpty().contains("ANTIGRAVITY_ACP_ENABLED"))
        }
    }

    @Test
    fun `enabled ACP uses requested defaults`() {
        val config = assertNotNull(AntigravityAcpConfig.fromEnv(requiredEnv))

        assertEquals(script, config.script)
        assertEquals(stateDir, config.stateDir)
        assertEquals(Path.of("/usr/bin/python3"), config.python)
        assertEquals("http://127.0.0.1:7897", config.proxy)
        assertEquals(modelId, config.modelId)
        assertEquals(setOf(firstKeyId), config.clientKeyIds)
        assertEquals(120_000L, config.timeoutMs)
    }

    @Test
    fun `constructor exposes the same defaults as environment loading`() {
        val config = AntigravityAcpConfig(
            script = script,
            stateDir = stateDir,
            modelId = modelId,
            clientKeyIds = setOf(firstKeyId),
        )

        assertEquals(Path.of("/usr/bin/python3"), config.python)
        assertEquals("http://127.0.0.1:7897", config.proxy)
        assertEquals(120_000L, config.timeoutMs)
    }

    @Test
    fun `all configured fields can be overridden through prefixed environment keys`() {
        val config = assertNotNull(AntigravityAcpConfig.fromEnv(requiredEnv + mapOf(
            "ANTIGRAVITY_ACP_SCRIPT" to "/opt/ACP tools/bridge.py",
            "ANTIGRAVITY_ACP_STATE_DIR" to "/var/lib/ACP state",
            "ANTIGRAVITY_ACP_PYTHON" to "/opt/python tools/bin/python3",
            "ANTIGRAVITY_ACP_PROXY" to "http://127.0.0.1:8080",
            "ANTIGRAVITY_ACP_MODEL" to "gemini-custom",
            "ANTIGRAVITY_ACP_CLIENT_KEY_IDS" to "$firstKeyId,$secondKeyId",
            "ANTIGRAVITY_ACP_TIMEOUT_MS" to "300000",
        )))

        assertEquals(Path.of("/opt/ACP tools/bridge.py"), config.script)
        assertEquals(Path.of("/var/lib/ACP state"), config.stateDir)
        assertEquals(Path.of("/opt/python tools/bin/python3"), config.python)
        assertEquals("http://127.0.0.1:8080", config.proxy)
        assertEquals("gemini-custom", config.modelId)
        assertEquals(setOf(firstKeyId, secondKeyId), config.clientKeyIds)
        assertEquals(300_000L, config.timeoutMs)
    }

    @Test
    fun `enabled ACP requires every mandatory setting`() {
        for (name in listOf("SCRIPT", "STATE_DIR", "MODEL", "CLIENT_KEY_IDS")) {
            val setting = "ANTIGRAVITY_ACP_$name"
            val failure = assertFailsWith<IllegalArgumentException> {
                AntigravityAcpConfig.fromEnv(requiredEnv - setting)
            }
            assertTrue(failure.message.orEmpty().contains(setting))
        }
    }

    @Test
    fun `paths must be absolute and contain no line breaks in either entry point`() {
        val invalidPaths = listOf("", "gateway.py", "./gateway.py", "../gateway.py", "~/gateway.py") +
            listOf("\n", "\r", "\r\n", "\u000B", "\u000C", "\u0085", "\u2028", "\u2029").map {
                "/opt/antigravity${it}/gateway.py"
            }

        for (value in invalidPaths) {
            val path = Path.of(value)
            assertRejected("SCRIPT", value) { createConfig(script = path) }
            assertRejected("STATE_DIR", value) { createConfig(stateDir = path) }
            assertRejected("PYTHON", value) { createConfig(python = path) }
        }
    }

    @Test
    fun `model identifiers accept the full allowed alphabet and inclusive length bounds`() {
        for (value in listOf("a", "A0._:/-", modelId, "a".repeat(160))) {
            assertEquals(value, createConfig(modelId = value).modelId)
            assertEquals(value, assertNotNull(AntigravityAcpConfig.fromEnv(
                requiredEnv + ("ANTIGRAVITY_ACP_MODEL" to value),
            )).modelId)
        }
    }

    @Test
    fun `model identifiers reject blanks invalid characters and more than 160 characters`() {
        for (value in listOf(
            "", " ", "a".repeat(161), "gemini pro", "gemini;echo", "gemini@pro", "gemini\n", "gemini\r", "gemini中文",
        )) {
            assertRejected("MODEL", value) { createConfig(modelId = value) }
        }
    }

    @Test
    fun `client key IDs are trimmed deduplicated and accept uppercase UUIDs`() {
        val uppercaseKeyId = secondKeyId.uppercase()
        val config = assertNotNull(AntigravityAcpConfig.fromEnv(
            requiredEnv + ("ANTIGRAVITY_ACP_CLIENT_KEY_IDS" to " $firstKeyId, $uppercaseKeyId ,$firstKeyId "),
        ))

        assertEquals(setOf(firstKeyId, uppercaseKeyId), config.clientKeyIds)
        assertEquals(setOf(uppercaseKeyId), createConfig(clientKeyIds = setOf(uppercaseKeyId)).clientKeyIds)
    }

    @Test
    fun `client key IDs reject empty entries malformed UUIDs and shortened UUIDs`() {
        for (value in listOf(
            "",
            " ",
            "not-a-uuid",
            "1-1-1-1-1",
            firstKeyId.replace("-", ""),
            firstKeyId.dropLast(1),
            "${firstKeyId}0",
            "$firstKeyId,not-a-uuid",
            "$firstKeyId,",
            ",$firstKeyId",
            "$firstKeyId, ,$secondKeyId",
        )) {
            assertRejected("CLIENT_KEY_IDS", value) {
                createConfig(clientKeyIds = value.split(',').map { it.trim() }.toSet())
            }
        }
        assertFailsWith<IllegalArgumentException> { createConfig(clientKeyIds = emptySet()) }
        assertFailsWith<IllegalArgumentException> { createConfig(clientKeyIds = setOf(" $firstKeyId ")) }
    }

    @Test
    fun `client key ID line breaks are rejected before trimming`() {
        for (lineBreak in listOf("\n", "\r", "\r\n", "\u000B", "\u000C", "\u0085", "\u2028", "\u2029")) {
            for (value in listOf(
                "$lineBreak$firstKeyId", "$firstKeyId$lineBreak", "$firstKeyId,$lineBreak$secondKeyId",
            )) {
                val failure = assertFailsWith<IllegalArgumentException> {
                    AntigravityAcpConfig.fromEnv(requiredEnv + ("ANTIGRAVITY_ACP_CLIENT_KEY_IDS" to value))
                }
                assertTrue(failure.message.orEmpty().contains("ANTIGRAVITY_ACP_CLIENT_KEY_IDS"))
            }
            assertFailsWith<IllegalArgumentException> {
                createConfig(clientKeyIds = setOf("$firstKeyId$lineBreak"))
            }
        }
    }

    @Test
    fun `proxy accepts only literal ipv4 loopback HTTP with an explicit valid port`() {
        for (value in listOf(
            "http://127.0.0.1:1", "http://127.0.0.1:7897",
            "http://127.0.0.1:65535", "HTTP://127.0.0.1:8080",
        )) {
            assertEquals(value, createConfig(proxy = value).proxy)
            assertEquals(value, assertNotNull(AntigravityAcpConfig.fromEnv(
                requiredEnv + ("ANTIGRAVITY_ACP_PROXY" to value),
            )).proxy)
        }
    }

    @Test
    fun `proxy rejects other hosts protocols credentials paths queries fragments and invalid ports`() {
        for (value in listOf(
            "",
            "127.0.0.1:7897",
            "http://127.0.0.1",
            "https://127.0.0.1:7897",
            "socks5://127.0.0.1:7897",
            "http://localhost:7897",
            "http://[::1]:7897",
            "http://127.0.0.2:7897",
            "http://2130706433:7897",
            "http://127.0.0.1.example.com:7897",
            "http://proxy.example.com:7897",
            "http://user:password@127.0.0.1:7897",
            "http://@127.0.0.1:7897",
            "http://127.0.0.1:7897/",
            "http://127.0.0.1:7897/path",
            "http://127.0.0.1:7897?",
            "http://127.0.0.1:7897?token=secret",
            "http://127.0.0.1:7897#",
            "http://127.0.0.1:7897#fragment",
            "http://127.0.0.1:",
            "http://127.0.0.1:0",
            "http://127.0.0.1:-1",
            "http://127.0.0.1:65536",
            "http://127.0.0.1:99999999999999999999",
            " http://127.0.0.1:7897",
            "http://127.0.0.1:7897 ",
            "http://127.0.0.1:7897\n",
            "http://127.0.0.1:7897\r",
        )) {
            assertRejected("PROXY", value) { createConfig(proxy = value) }
        }
    }

    @Test
    fun `timeout accepts both inclusive bounds`() {
        for (value in listOf(1_000L, 600_000L)) {
            assertEquals(value, createConfig(timeoutMs = value).timeoutMs)
            assertEquals(value, assertNotNull(AntigravityAcpConfig.fromEnv(
                requiredEnv + ("ANTIGRAVITY_ACP_TIMEOUT_MS" to value.toString()),
            )).timeoutMs)
        }
    }

    @Test
    fun `timeout rejects values outside bounds in either entry point`() {
        for (value in listOf(Long.MIN_VALUE, -1L, 0L, 999L, 600_001L, Long.MAX_VALUE)) {
            assertRejected("TIMEOUT_MS", value.toString()) { createConfig(timeoutMs = value) }
        }
    }

    @Test
    fun `timeout rejects blank noninteger overflowing and newline environment values`() {
        for (value in listOf(
            "", " ", "soon", "1000.0", "1e3", "9223372036854775808", " 1000", "1000 ", "1000\n", "1000\r",
        )) {
            val failure = assertFailsWith<IllegalArgumentException> {
                AntigravityAcpConfig.fromEnv(requiredEnv + ("ANTIGRAVITY_ACP_TIMEOUT_MS" to value))
            }
            assertTrue(failure.message.orEmpty().contains("ANTIGRAVITY_ACP_TIMEOUT_MS"))
        }
    }

    @Test
    fun `command preserves the specified argument order and paths with spaces`() {
        val config = createConfig(
            script = Path.of("/opt/ACP tools/bridge.py"),
            stateDir = Path.of("/var/lib/ACP state"),
            python = Path.of("/opt/python tools/bin/python3"),
            proxy = "http://127.0.0.1:8080",
        )

        assertEquals(listOf(
            "/opt/python tools/bin/python3",
            "/opt/ACP tools/bridge.py",
            "--state",
            "/var/lib/ACP state",
            "--proxy",
            "http://127.0.0.1:8080",
            "gateway-agent",
            "--model",
            modelId,
        ), config.command())
    }

    @Test
    fun `validated client key IDs cannot be changed through mutable set aliases`() {
        val clientKeyIds = mutableSetOf(firstKeyId)
        val config = createConfig(clientKeyIds = clientKeyIds)

        clientKeyIds.clear()
        clientKeyIds.add("not-a-uuid")

        assertEquals(setOf(firstKeyId), config.clientKeyIds)
        assertFailsWith<UnsupportedOperationException> {
            (config.clientKeyIds as MutableSet<String>).add("not-a-uuid")
        }
    }

    private fun createConfig(
        script: Path = this.script,
        stateDir: Path = this.stateDir,
        python: Path = Path.of("/usr/bin/python3"),
        proxy: String = "http://127.0.0.1:7897",
        modelId: String = this.modelId,
        clientKeyIds: Set<String> = setOf(firstKeyId),
        timeoutMs: Long = 120_000L,
    ): AntigravityAcpConfig = AntigravityAcpConfig(
        script = script,
        stateDir = stateDir,
        python = python,
        proxy = proxy,
        modelId = modelId,
        clientKeyIds = clientKeyIds,
        timeoutMs = timeoutMs,
    )

    private fun assertRejected(name: String, value: String, construct: () -> AntigravityAcpConfig) {
        val setting = "ANTIGRAVITY_ACP_$name"
        val envFailure = assertFailsWith<IllegalArgumentException>("$setting must reject <$value>") {
            AntigravityAcpConfig.fromEnv(requiredEnv + (setting to value))
        }
        assertTrue(envFailure.message.orEmpty().contains(setting))
        val constructorFailure = assertFailsWith<IllegalArgumentException>("$setting must reject <$value>") {
            construct()
        }
        assertTrue(constructorFailure.message.orEmpty().contains(setting))
    }
}
