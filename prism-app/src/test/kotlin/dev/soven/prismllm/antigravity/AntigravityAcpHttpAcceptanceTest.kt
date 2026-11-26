package dev.soven.prismllm.antigravity

import dev.soven.prismllm.config.AntigravityAcpConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("component")
@Timeout(45)
class AntigravityAcpHttpAcceptanceTest {
    @TempDir
    lateinit var fixture: Path

    @ParameterizedTest
    @ValueSource(strings = ["http-success", "http-refresh", "http-quota-1", "http-quota-2", "http-quota-3",
        "http-disabled", "http-model-mismatch", "http-cancel-unacknowledged"])
    fun isolatedHttpAcceptanceStopsAtTheFirstFailureAndAlwaysRetiresItsKey(scenario: String) = runBlocking {
        val model = "gemini-fixed"
        val config = AntigravityAcpConfig(
            script = fixture.resolve("unused.py"), stateDir = fixture, modelId = model,
            clientKeyIds = setOf(UUID.randomUUID().toString()), timeoutMs = 8_000,
        )
        val classpath = (System.getProperty("surefire.test.class.path") ?: System.getProperty("java.class.path"))
            .split(File.pathSeparatorChar).joinToString(File.pathSeparator) { Path.of(it).toAbsolutePath().toString() }
        val pilot = AntigravityAcpPilot(
            command = listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dstdout.encoding=UTF-8", "-cp", classpath, FakeAcpGatewayAgent::class.java.name,
                fixture.toString(), scenario),
            workspace = fixture, agentWorkspace = Path.of("/workspace"), timeoutMs = 8_000,
            cancelGraceMs = 3_000,
        )
        lateinit var report: JsonObject
        val stages = mutableListOf<String>()
        val result = runAcpHttpAcceptance(config, pilot, writeReport = { report = it }, onStage = { stages += it })
        val passed = scenario in setOf("http-success", "http-refresh")
        assertEquals(if (passed) 0 else 1, result, report.toString())
        val expectedStatus = when (scenario) {
            "http-success", "http-refresh" -> "REAL_HTTP_ACP_ACCEPTANCE_PASSED"
            "http-disabled" -> "antigravity_acp_service_disabled"
            "http-model-mismatch" -> "antigravity_acp_model_mismatch"
            "http-cancel-unacknowledged" -> "CANCELLATION_NOT_ACKNOWLEDGED"
            else -> "antigravity_acp_quota_exhausted"
        }
        assertEquals(JsonPrimitive(expectedStatus), report["status"], report.toString())
        val attempts = when (scenario) {
            "http-quota-1", "http-disabled", "http-model-mismatch" -> 1
            "http-quota-2" -> 2
            else -> 3
        }
        assertEquals(JsonPrimitive(attempts), report["generationRequests"])
        assertEquals((if (passed) 4 else attempts).toString(), Files.readString(fixture.resolve("startups")))
        listOf("loopbackOnly", "productionRoutesUntouched", "ordinaryRoutesAbsent", "dedicatedCallerKeyVerified",
            "dedicatedCallerKeyRetired", "concurrencyReleased", "agentProcessesStopped", "httpListenerStopped")
            .forEach { assertEquals(JsonPrimitive(true), report[it], it + report) }
        listOf("productionDatabaseUsed", "extraCredits", "subscriptionEntitlementVerified", "refreshAfterExpiryVerified")
            .forEach { assertEquals(JsonPrimitive(false), report[it], it + report) }
        listOf("retries", "modelSwitches", "fallbacks").forEach { assertEquals(JsonPrimitive(0), report[it]) }
        assertEquals(JsonPrimitive(passed), report["cancelAcknowledged"])
        assertEquals(JsonPrimitive(passed), report["credentialsReusedAfterCancel"])
        if (scenario == "http-refresh") {
            assertTrue(Files.exists(fixture.resolve("synthetic-cli-refreshed-expired-login")))
            assertEquals(JsonPrimitive(false), report["refreshAfterExpiryVerified"])
        }
        assertFalse(report.toString().contains("private-fixture") || report.toString().contains("sk-gw-"))
        assertEquals(passed, "LOGIN_REUSE_AFTER_CANCEL" in stages)
        val methods = Files.readAllLines(fixture.resolve("methods"))
        assertTrue(methods.all { it in setOf("initialize", "session/new", "session/prompt", "session/cancel") })
        assertEquals(if (scenario == "http-model-mismatch") 0 else attempts, methods.count { it == "session/prompt" })
    }
}
