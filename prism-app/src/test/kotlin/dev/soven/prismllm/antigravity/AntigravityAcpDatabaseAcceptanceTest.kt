package dev.soven.prismllm.antigravity

import dev.soven.prismllm.config.AntigravityAcpConfig
import dev.soven.prismllm.quota.persistence.ClientKeyRepository
import dev.soven.prismllm.testsupport.db.SharedPostgres
import dev.soven.prismllm.testsupport.db.TestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("component")
@Timeout(45)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AntigravityAcpDatabaseAcceptanceTest {
    @TempDir
    lateinit var fixture: Path
    private lateinit var factory: TestDatabase

    @BeforeAll
    fun start() {
        val postgres = SharedPostgres.container
        factory = TestDatabase(SharedPostgres.newDatabase(), postgres.username, postgres.password)
    }

    @AfterAll
    fun stop() { factory.close() }

    @ParameterizedTest
    @ValueSource(strings = ["http-success", "http-quota-1", "http-model-mismatch"])
    fun realDatabaseAuthenticationLifecycleUsesOnlyOwnedKeysAndStopsWithoutRetry(scenario: String) = runBlocking {
        val repository = ClientKeyRepository(factory.database)
        val sentinel = repository.create("unrelated-fixture-key", "sentinel-hash", "sentinel", true, null)
        val classpath = (System.getProperty("surefire.test.class.path") ?: System.getProperty("java.class.path"))
            .split(File.pathSeparatorChar).joinToString(File.pathSeparator) { Path.of(it).toAbsolutePath().toString() }
        lateinit var report: JsonObject
        try {
            val result = runAcpDatabaseAcceptance(factory.database, fixture.resolve("unused.py"), fixture, "gemini-fixed",
                pilotFactory = { config: AntigravityAcpConfig ->
                    AntigravityAcpPilot(command = listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Dstdout.encoding=UTF-8", "-cp", classpath, FakeAcpGatewayAgent::class.java.name,
                        fixture.toString(), scenario), workspace = fixture, agentWorkspace = Path.of("/workspace"),
                        timeoutMs = config.timeoutMs)
                }, writeReport = { report = it })
            assertEquals(if (scenario == "http-success") 0 else 1, result, report.toString())
            assertEquals(JsonPrimitive(when (scenario) {
                "http-success" -> "REAL_DATABASE_ACP_ACCEPTANCE_PASSED"
                "http-quota-1" -> "antigravity_acp_quota_exhausted"
                else -> "antigravity_acp_model_mismatch"
            }), report["status"], report.toString())
            listOf("databaseBackedAuthentication", "productionRoutesUntouched", "loopbackOnly", "dedicatedRecordsDeleted",
                "concurrencyReleased", "requestsFinalized", "httpListenerStopped").forEach {
                assertEquals(JsonPrimitive(true), report[it], it + report)
            }
            assertEquals(JsonPrimitive(1), report["generationRequests"])
            assertEquals(JsonPrimitive(false), report["productionDatabaseUsed"])
            assertEquals(JsonPrimitive(false), report["migrationsRun"])
            assertEquals(listOf(sentinel), repository.list())
            assertEquals("1", Files.readString(fixture.resolve("startups")))
            val methods = Files.readAllLines(fixture.resolve("methods"))
            assertTrue(methods.all { it in setOf("initialize", "session/new", "session/prompt", "session/cancel") })
            assertEquals(if (scenario == "http-model-mismatch") 0 else 1, methods.count { it == "session/prompt" })
            assertFalse(report.toString().contains("sk-gw-") || report.toString().contains("sentinel-hash"))
        } finally {
            repository.delete(sentinel.id)
        }
    }
}
