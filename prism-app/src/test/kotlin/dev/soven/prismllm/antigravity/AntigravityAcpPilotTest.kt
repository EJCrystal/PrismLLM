package dev.soven.prismllm.antigravity

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import kotlin.system.exitProcess
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("component")
@Timeout(15)
class AntigravityAcpPilotTest {
    @TempDir
    lateinit var fixture: Path

    @Test
    fun streamsOnlyTextFromTheActiveTurnAndDeniesTools() = runBlocking {
        val chunks = mutableListOf<String>()

        val reason = pilot("stream").prompt(FIXTURE_PROMPT, modelId = "gemini-pilot") { chunk ->
            chunks += chunk
            if (chunk == "第一段\n") Files.createFile(fixture.resolve("first-chunk"))
        }

        assertEquals(listOf("第一段\n", "第二段🌿"), chunks)
        assertEquals(AcpStopReason.END_TURN, reason)
        assertChildStopped()
    }

    @Test
    fun cancellingCallerNotifiesAgentAndDeclinesPendingPermission() = runBlocking {
        val pilot = pilot("cancel")
        val started = CompletableDeferred<Unit>()
        val chunks = mutableListOf<String>()
        var acknowledged = false
        val turn = launch {
            pilot.prompt(FIXTURE_PROMPT, modelId = "gemini-pilot", onCancelled = { acknowledged = it }) { chunk ->
                chunks += chunk
                started.complete(Unit)
                awaitCancellation()
            }
        }

        started.await()
        val busy = assertFailsWith<AcpPilotException> { pilot.prompt(FIXTURE_PROMPT, modelId = "gemini-pilot") {} }
        assertEquals(AcpPilotException.Kind.BUSY, busy.kind)
        turn.cancelAndJoin()

        assertTrue(turn.isCancelled)
        assertTrue(acknowledged)
        assertEquals(listOf("partial"), chunks)
        assertTrue(Files.exists(fixture.resolve("cancel-acknowledged")))
        assertChildStopped()
    }

    @ParameterizedTest
    @ValueSource(strings = ["catalog-config", "catalog-legacy"])
    fun inspectsOnlyTheModelsActuallyOfferedByTheSession(scenario: String) = runBlocking {
        val catalog = pilot(scenario).inspect()
        assertEquals(listOf("gemini-pilot", "gemini-other"), catalog.modelIds)
        assertEquals("gemini-other", catalog.currentModelId)
        assertChildStopped()
    }

    @ParameterizedTest
    @ValueSource(strings = ["pro", "free", "missing", "malformed", "id-only", "capability-only", "model-name", "mismatch"])
    fun accountStatusUsesOnlyOfficialSessionTierMetadataAndNeverGeneratesOrSelectsModels(suffix: String) = runBlocking {
        val workspace = Files.createDirectory(fixture.resolve("workspace")).toRealPath()
        val arguments = listOf("account-status", workspace.toString(), "gemini-pilot", "--") + fixtureCommand("runner-account-" + suffix)
        val reports = mutableListOf<JsonObject>()
        val result = runAcpAcceptance(arguments.toTypedArray()) { reports += it }
        val report = reports.single()
        assertEquals(if (suffix == "mismatch") 1 else 0, result)
        assertEquals(JsonPrimitive(if (suffix == "mismatch") "MODEL_MISMATCH" else "OFFICIAL_ACP_ACCOUNT_STATUS_VERIFIED"), report["status"])
        assertEquals(JsonPrimitive(suffix == "pro"), report["subscriptionEntitlementVerified"])
        assertEquals(when (suffix) {
            "pro" -> JsonPrimitive("PRO")
            "free" -> JsonPrimitive("FREE")
            else -> null
        }, report["subscriptionTier"])
        assertEquals(JsonPrimitive(false), report["realGenerationVerified"])
        assertEquals(JsonPrimitive(false), report["refreshAfterExpiryVerified"])
        assertFalse(report.toString().contains(PRIVATE_FIXTURE_DATA))
        assertFalse(report.toString().contains("upgradeUri"))
        assertEquals("1", Files.readString(fixture.resolve("startups")))
        assertChildStopped()
    }

    @ParameterizedTest
    @ValueSource(strings = ["current-model-config", "current-model-legacy"])
    fun promptsOnlyWithTheAlreadyCurrentModelWithoutSelectingIt(scenario: String) = runBlocking {
        val chunks = mutableListOf<String>()
        val reason = pilot(scenario).prompt(FIXTURE_PROMPT, modelId = "gemini-pilot") { chunks += it }
        assertEquals(AcpStopReason.END_TURN, reason)
        assertEquals(listOf("current model"), chunks)
        assertEquals(1, receivedMethods().count { it == "session/prompt" })
        assertChildStopped()
    }

    @Test
    fun rejectsAnUnavailableModelWithoutPromptingOrFallingBack() = runBlocking {
        val error = assertFailsWith<AcpPilotException> {
            pilot("unavailable-model").prompt(FIXTURE_PROMPT, modelId = "not-available") {
                error("No prompt should have been sent")
            }
        }
        assertEquals(AcpPilotException.Kind.MODEL_UNAVAILABLE, error.kind)
        assertFalse("session/prompt" in receivedMethods())
        assertChildStopped()
    }

    @ParameterizedTest
    @ValueSource(strings = ["catalog-config", "catalog-legacy"])
    fun neverChangesTheCurrentModelOrPromptsWhenTheTargetDiffers(scenario: String) = runBlocking {
        val failure = assertFailsWith<AcpPilotException> {
            pilot(scenario).prompt(FIXTURE_PROMPT, modelId = "gemini-pilot") {}
        }
        assertEquals(AcpPilotException.Kind.MODEL_MISMATCH, failure.kind)
        assertFalse("session/prompt" in receivedMethods())
        assertChildStopped()
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "runner-discovery-mismatch-config", "runner-discovery-mismatch-legacy",
        "runner-generation-mismatch-config", "runner-generation-mismatch-legacy",
        "runner-cancellation-mismatch-config", "runner-cancellation-mismatch-legacy",
        "runner-reuse-mismatch-config", "runner-reuse-mismatch-legacy",
    ])
    fun acceptanceStopsAtEachModelMismatchWithoutSelectingOrSendingAnotherPrompt(scenario: String) = runBlocking {
        val workspace = Files.createDirectory(fixture.resolve("workspace")).toRealPath()
        val arguments = listOf("verify", workspace.toString(), "gemini-pilot", "--") + fixtureCommand(scenario)
        val reports = mutableListOf<JsonObject>()

        val result = runAcpAcceptance(arguments.toTypedArray()) { reports += it }

        val stage = scenario.removePrefix("runner-").substringBefore("-mismatch-")
        val startup = when (stage) {
            "discovery" -> 1
            "generation" -> 2
            "cancellation" -> 3
            "reuse" -> 4
            else -> error("Unknown acceptance stage")
        }
        val report = reports.single()
        assertEquals(1, result)
        assertEquals(JsonPrimitive("MODEL_MISMATCH"), report["status"])
        assertEquals(JsonPrimitive(when (stage) {
            "discovery" -> "SESSION_DISCOVERY"
            "generation" -> "TEXT_GENERATION"
            "cancellation" -> "CANCELLATION"
            else -> "CREDENTIAL_REUSE"
        }), report["stage"])
        assertEquals(JsonPrimitive(startup >= 3), report["realGenerationVerified"])
        assertFalse(report["credentialsReused"] == JsonPrimitive(true))
        assertFalse(report["cancelAcknowledged"] == JsonPrimitive(true))
        assertEquals(startup.toString(), Files.readString(fixture.resolve("startups")))
        assertEquals((startup - 2).coerceAtLeast(0), receivedMethods().count { it == "session/prompt" })
        assertFalse(report.toString().contains(PRIVATE_FIXTURE_DATA))
        assertChildStopped()
    }

    @ParameterizedTest
    @ValueSource(strings = [
        """{"sessionUpdate":"current_model_update","currentModelId":"gemini-other"}""",
        """{"sessionUpdate":"current_model_update"}""",
        """{"sessionUpdate":"current_model_update","currentModelId":null}""",
        """{"sessionUpdate":"current_model_update","currentModelId":42}""",
        """{"sessionUpdate":"config_option_update","configOptions":[{"id":"selected-model","currentValue":"gemini-other"}]}""",
        """{"sessionUpdate":"config_option_update","configOptions":[{"id":"selected-model"}]}""",
        """{"sessionUpdate":"config_option_update","configOptions":[{"id":"selected-model","currentValue":null}]}""",
        """{"sessionUpdate":"config_option_update","configOptions":[{"id":"selected-model","currentValue":42}]}""",
        """{"sessionUpdate":"config_option_update","configOptions":[{"id":"selected-model","currentValue":{"value":"gemini-pilot"}}]}""",
        """{"sessionUpdate":"config_option_update","configOptions":[{"id":"selected-model","category":"other","currentValue":"gemini-other"}]}""",
        """{"sessionUpdate":"config_option_update","configOptions":[{"id":"unrelated"},{"id":"selected-model","currentValue":"gemini-other"}]}""",
        """{"sessionUpdate":"config_option_update","configOptions":[{"id":"other-id","category":"model","currentValue":"gemini-other"}]}""",
        """{"sessionUpdate":"config_option_update","configOptions":[{"category":"model"}]}""",
        """{"sessionUpdate":"config_option_update","configOptions":[{"id":"model","currentValue":"gemini-other"}]}""",
        """{"sessionUpdate":"config_option_update","configOptions":[{"id":"model"}]}""",
    ])
    fun fixedModelDriftCancelsBeforeAnySubsequentText(update: String) = runBlocking {
        assertFixedModelNoticeRejected("fixed-drift:$update", AcpPilotException.Kind.MODEL_MISMATCH)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        """{"sessionUpdate":"config_option_update"}""",
        """{"sessionUpdate":"config_option_update","configOptions":null}""",
        """{"sessionUpdate":"config_option_update","configOptions":{"id":"selected-model","currentValue":"gemini-other"}}""",
        """{"sessionUpdate":"config_option_update","configOptions":[null]}""",
        """{"sessionUpdate":"config_option_update","configOptions":["selected-model"]}""",
        """{"sessionUpdate":"config_option_update","configOptions":[{"id":"unrelated"},42]}""",
    ])
    fun malformedFixedModelNoticesFailClosedBeforeAnySubsequentText(update: String) = runBlocking {
        assertFixedModelNoticeRejected("fixed-invalid:$update", AcpPilotException.Kind.PROTOCOL_ERROR)
    }

    @ParameterizedTest
    @ValueSource(strings = ["fixed-notices-config", "fixed-notices-legacy"])
    fun sameModelForeignSessionAndUnrelatedNoticesDoNotInterruptTheTurn(scenario: String) = runBlocking {
        val chunks = mutableListOf<String>()
        var cancelled = false
        val reason = pilot(scenario).prompt(
            FIXTURE_PROMPT, modelId = "gemini-pilot", onCancelled = { cancelled = true },
        ) { chunks += it }

        assertEquals(AcpStopReason.END_TURN, reason)
        assertEquals(listOf("before notices", "after notices"), chunks)
        assertFalse(cancelled)
        assertChildStopped()
    }

    @ParameterizedTest
    @ValueSource(strings = ["runner-pass", "runner-cancel-incomplete", "runner-auth-required", "runner-disabled-text",
        "runner-quota-text", "runner-disabled-rpc", "runner-quota-rpc", "runner-unexpected-text", "runner-cancel-quota"])
    fun acceptanceEntryUsesRealTransportAndReportsOnlyCompletedChecks(scenario: String) = runBlocking {
        val workspace = Files.createDirectory(fixture.resolve("workspace")).toRealPath()
        val arguments = listOf("verify", workspace.toString(), "gemini-pilot", "--") + fixtureCommand(scenario)
        val reports = mutableListOf<JsonObject>()
        val result = runAcpAcceptance(arguments.toTypedArray()) { reports += it }
        assertEquals(when (scenario) {
            "runner-pass" -> 0
            "runner-auth-required" -> 10
            else -> 1
        }, result)
        val report = reports.single()
        val status = when (scenario) {
            "runner-pass" -> "REAL_ACP_ACCEPTANCE_PASSED"
            "runner-auth-required" -> "AUTHENTICATION_REQUIRED_OR_DENIED"
            "runner-disabled-text", "runner-disabled-rpc" -> "SERVICE_DISABLED"
            "runner-quota-text", "runner-quota-rpc", "runner-cancel-quota" -> "QUOTA_EXHAUSTED"
            "runner-unexpected-text" -> "UNEXPECTED_GENERATION_RESPONSE"
            else -> "CANCELLATION_NOT_ACKNOWLEDGED"
        }
        assertEquals(JsonPrimitive(status), report["status"])
        assertEquals(JsonPrimitive(scenario in setOf("runner-pass", "runner-cancel-incomplete", "runner-cancel-quota")),
            report["realGenerationVerified"])
        assertFalse(report.toString().contains(PRIVATE_FIXTURE_DATA))
        assertFalse(report.toString().contains("PRISM-"))
        if (scenario.contains("disabled") || scenario in setOf("runner-quota-text", "runner-quota-rpc", "runner-unexpected-text")) {
            assertEquals("2", Files.readString(fixture.resolve("startups")))
        }
        assertChildStopped()
    }

    @Test
    fun timesOutAndStopsAgentThatIgnoresCancellation() = runBlocking {
        val chunks = mutableListOf<String>()
        val error = assertFailsWith<AcpPilotException> {
            pilot("hang", timeoutMs = 2_000).prompt(FIXTURE_PROMPT, modelId = "gemini-pilot") { chunks += it }
        }

        assertEquals(AcpPilotException.Kind.TIMED_OUT, error.kind)
        assertEquals(listOf("partial"), chunks)
        assertTrue(Files.exists(fixture.resolve("cancel-received")))
        assertChildStopped()
    }

    @Test
    fun timesOutEvenWhenAgentStopsReadingStdin() = runBlocking {
        val error = assertFailsWith<AcpPilotException> {
            pilot("blocked-writer", timeoutMs = 2_000).prompt("\u0000".repeat(32_768), modelId = "gemini-pilot") {
                error("A blocked agent did not send a chunk")
            }
        }

        assertEquals(AcpPilotException.Kind.TIMED_OUT, error.kind)
        assertTrue(Files.exists(fixture.resolve("stdin-blocked")))
        assertChildStopped()
    }

    @Test
    fun reportsProcessExitAfterPartialOutput() = runBlocking {
        val chunks = mutableListOf<String>()
        val error = assertFailsWith<AcpPilotException> {
            pilot("exit").prompt(FIXTURE_PROMPT, modelId = "gemini-pilot") { chunks += it }
        }

        assertEquals(AcpPilotException.Kind.TRANSPORT_CLOSED, error.kind)
        assertEquals(listOf("partial"), chunks)
        assertChildStopped()
    }

    @Test
    fun reportsRpcCodeWithoutExposingRawAgentErrors() = runBlocking {
        val chunks = mutableListOf<String>()
        val error = assertFailsWith<AcpPilotException> {
            pilot("error").prompt(FIXTURE_PROMPT, modelId = "gemini-pilot") { chunks += it }
        }

        assertEquals(AcpPilotException.Kind.AGENT_ERROR, error.kind)
        assertEquals(-32000, error.rpcCode)
        assertFalse(error.message.orEmpty().contains(PRIVATE_FIXTURE_DATA))
        assertNull(error.cause)
        assertEquals(listOf("partial"), chunks)
        assertChildStopped()
    }

    @ParameterizedTest
    @ValueSource(strings = ["wrong-version", "invalid-json", "oversized-frame"])
    fun rejectsUnsupportedOrInvalidFrames(scenario: String) = runBlocking {
        val error = assertFailsWith<AcpPilotException> {
            pilot(scenario).prompt(FIXTURE_PROMPT, modelId = "gemini-pilot") { error("An invalid frame did not produce a chunk") }
        }

        assertEquals(AcpPilotException.Kind.PROTOCOL_ERROR, error.kind)
        assertFalse(error.message.orEmpty().contains(PRIVATE_FIXTURE_DATA))
        assertNull(error.cause)
        assertChildStopped()
    }

    private suspend fun assertFixedModelNoticeRejected(scenario: String, kind: AcpPilotException.Kind) {
        val chunks = mutableListOf<String>()
        var acknowledged = false
        val failure = assertFailsWith<AcpPilotException> {
            pilot(scenario).prompt(
                FIXTURE_PROMPT, modelId = "gemini-pilot", onCancelled = { acknowledged = it },
            ) { chunks += it }
        }

        assertEquals(kind, failure.kind)
        assertEquals(listOf("before update"), chunks)
        assertTrue(acknowledged)
        assertTrue(Files.exists(fixture.resolve("cancel-acknowledged")))
        assertChildStopped()
    }

    private fun pilot(scenario: String, timeoutMs: Long = 8_000) = AntigravityAcpPilot(
        command = fixtureCommand(scenario),
        workspace = Files.createDirectory(fixture.resolve("workspace")).toRealPath(),
        environment = mapOf("PRISM_ACP_FIXTURE" to "enabled"),
        timeoutMs = timeoutMs,
    )

    private fun fixtureCommand(scenario: String) = listOf(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Dstdout.encoding=UTF-8",
            "-cp", testClasspath(),
            FakeAcpAgent::class.java.name, scenario, fixture.toRealPath().toString(),
    )

    private fun testClasspath(): String {
        val classpath = System.getProperty("surefire.test.class.path")?.takeIf(String::isNotBlank)
            ?: System.getProperty("java.class.path")
        return classpath.split(File.pathSeparatorChar).joinToString(File.pathSeparator) {
            Path.of(it).toAbsolutePath().normalize().toString()
        }
    }

    private fun assertChildStopped() {
        val pid = Files.readString(fixture.resolve("pid")).toLong()
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "Fake ACP child is still running")
        assertFalse("session/set_model" in receivedMethods())
        assertFalse("session/set_config_option" in receivedMethods())
    }

    private fun receivedMethods(): List<String> = Files.readAllLines(fixture.resolve("methods"))
}

private object FakeAcpAgent {
    @JvmStatic
    fun main(args: Array<String>) {
        val (scenario, fixturePath) = args
        val fixture = Path.of(fixturePath)
        val workspace = fixture.resolve("workspace")
        Files.writeString(fixture.resolve("pid"), ProcessHandle.current().pid().toString())
        check(Path.of("").toRealPath() == workspace)
        if (!scenario.startsWith("runner-")) check(System.getenv("PRISM_ACP_FIXTURE") == "enabled")
        check(System.getenv("PATH") == null && System.getenv("GEMINI_API_KEY") == null)
        val wire = FakeWire(fixture)

        val init = wire.request("initialize")
        check(init["params"] == json("""{"protocolVersion":1,"clientCapabilities":{"fs":{"readTextFile":false,"writeTextFile":false},"terminal":false,"auth":{"terminal":false}}}"""))
        when (scenario) {
            "wrong-version" -> return wire.result(init.getValue("id"), """{"protocolVersion":2}""")
            "invalid-json" -> return println("{" + PRIVATE_FIXTURE_DATA)
            "oversized-frame" -> return println(" ".repeat(1_048_577))
        }
        val capability = if (scenario == "runner-account-capability-only")
            """{"_meta":{"antigravity":{"tier":{"name":"Google AI Pro"}}}}""" else "{}"
        wire.result(init.getValue("id"), """{"protocolVersion":1,"agentCapabilities":$capability,"authMethods":[]}""")

        val new = wire.request("session/new")
        val agentWorkspace = if (scenario.startsWith("runner-")) "/workspace" else workspace.toString()
        check(new["params"] == json("""{"cwd":${JsonPrimitive(agentWorkspace)},"mcpServers":[]}"""))
        if (scenario == "runner-auth-required") {
            wire.write(""""id":${new.getValue("id")},"error":{"code":-32000,"message":"$PRIVATE_FIXTURE_DATA"}""")
            return
        }
        if (scenario.startsWith("runner-account-")) {
            val tier = when (scenario.removePrefix("runner-account-")) {
                "pro" -> """{"name":"Google AI Pro","id":"fixture-pro","upgradeUri":"$PRIVATE_FIXTURE_DATA"}"""
                "free" -> """{"name":"Free"}"""
                "malformed" -> """{"name":{"private":"$PRIVATE_FIXTURE_DATA"}}"""
                "id-only" -> """{"id":"pro"}"""
                else -> "{}"
            }
            val model = if (scenario == "runner-account-mismatch") "gemini-other" else "gemini-pilot"
            val name = if (scenario == "runner-account-model-name") "Google AI Pro" else "Pilot"
            wire.result(new.getValue("id"), """{"sessionId":"fixture-session","models":{"currentModelId":"$model","availableModels":[{"modelId":"gemini-pilot","name":"$name"},{"modelId":"gemini-other","name":"Other"}]},"_meta":{"antigravity":{"tier":$tier}}}""")
            Files.writeString(fixture.resolve("startups"), "1")
            return
        }
        val startup = if (scenario.startsWith("runner-")) {
            val counter = fixture.resolve("startups")
            val count = if (Files.exists(counter)) Files.readString(counter).toInt() + 1 else 1
            Files.writeString(counter, count.toString())
            count
        } else 0
        val mismatchStartup = when (scenario.removePrefix("runner-").substringBefore("-mismatch-")) {
            "discovery" -> 1
            "generation" -> 2
            "cancellation" -> 3
            "reuse" -> 4
            else -> -1
        }
        val currentModel = if (startup == mismatchStartup || scenario.startsWith("catalog-") || scenario == "unavailable-model")
            "gemini-other" else "gemini-pilot"
        wire.chunk("before the prompt")
        when {
            scenario == "fixed-notices-legacy" -> wire.result(new.getValue("id"),
                """{"sessionId":"fixture-session","models":{"currentModelId":"gemini-pilot","availableModels":[{"modelId":"gemini-pilot","name":"Pilot"}]}}""")
            scenario.startsWith("fixed-") -> wire.result(new.getValue("id"),
                """{"sessionId":"fixture-session","configOptions":[{"id":"selected-model","category":"model","type":"select","currentValue":"gemini-pilot","options":[{"value":"gemini-pilot","name":"Pilot"},{"value":"gemini-other","name":"Other"}]}]}""")
            scenario.endsWith("config") || scenario == "unavailable-model" -> wire.result(new.getValue("id"),
                """{"sessionId":"fixture-session","configOptions":[{"id":"model","category":"model","type":"select","currentValue":"$currentModel","options":[{"name":"Group","options":[{"value":"gemini-pilot","name":"Pilot"},{"value":"gemini-other","name":"Other"},{"value":"invalid\nmodel","name":"Invalid"}]}]}]}""")
            else -> wire.result(new.getValue("id"),
                """{"sessionId":"fixture-session","models":{"currentModelId":"$currentModel","availableModels":[{"modelId":"gemini-pilot","name":"Pilot"},{"modelId":"gemini-other","name":"Other"}]}}""")
        }
        if (scenario.startsWith("runner-")) {
            if (startup == 1 || startup == 4) return
            val prompt = wire.request("session/prompt")
            val promptId = prompt.getValue("id")
            val parts = prompt.getValue("params").jsonObject["prompt"] as JsonArray
            val text = (parts.single().jsonObject["text"] as JsonPrimitive).content
            if (startup == 2) {
                val marker = checkNotNull(Regex("PRISM-[a-f0-9-]+").find(text)).value
                when (scenario) {
                    "runner-disabled-text" -> {
                        wire.chunk("This service has been disabled in this account")
                        wire.chunk(" for violation of Terms of Service $PRIVATE_FIXTURE_DATA")
                    }
                    "runner-quota-text" -> wire.chunk("RESOURCE_EXHAUSTED $PRIVATE_FIXTURE_DATA")
                    "runner-disabled-rpc", "runner-quota-rpc" -> {
                        val failure = if (scenario == "runner-disabled-rpc")
                            "This service has been disabled in this account for violation of Terms of Service"
                        else "RESOURCE_EXHAUSTED"
                        wire.write(""""id":$promptId,"error":{"code":-32000,"message":"$failure $PRIVATE_FIXTURE_DATA"}""")
                        return
                    }
                    "runner-unexpected-text" -> wire.chunk(PRIVATE_FIXTURE_DATA)
                    else -> wire.chunk(marker)
                }
                wire.result(promptId, """{"stopReason":"end_turn"}""")
            } else {
                check(startup == 3 && text.startsWith("Print the numbers"))
                if (scenario == "runner-cancel-quota") {
                    wire.chunk("RESOURCE_EXHAUSTED $PRIVATE_FIXTURE_DATA")
                    wire.result(promptId, """{"stopReason":"end_turn"}""")
                    return
                }
                wire.chunk("1\n")
                wire.expectCancel()
                val reason = if (scenario == "runner-pass" || scenario.contains("-mismatch-")) "cancelled" else "end_turn"
                wire.result(promptId, """{"stopReason":"$reason"}""")
            }
            return
        }
        if (scenario.startsWith("catalog-")) return
        if (scenario == "blocked-writer") {
            Files.createFile(fixture.resolve("stdin-blocked"))
            return Thread.sleep(Long.MAX_VALUE)
        }

        val prompt = wire.request("session/prompt")
        val promptId = prompt.getValue("id")
        check(prompt["params"] == json(
            """{"sessionId":"fixture-session","prompt":[{"type":"text","text":${JsonPrimitive(FIXTURE_PROMPT)}}]}""",
        ))
        if (scenario.startsWith("fixed-drift:") || scenario.startsWith("fixed-invalid:")) {
            wire.chunk("before update")
            wire.update(scenario.substringAfter(':'))
            wire.chunk("discarded after drift")
            wire.permission("after-drift", GOOD_OPTIONS)
            wire.expectCancel()
            wire.expectPermission("after-drift", """{"outcome":"cancelled"}""")
            wire.update("""{"sessionUpdate":"current_model_update"}""")
            wire.update("""{"sessionUpdate":"config_option_update","configOptions":[{"id":"selected-model"}]}""")
            wire.chunk("discarded during cancellation")
            Files.createFile(fixture.resolve("cancel-acknowledged"))
            wire.result(promptId, """{"stopReason":"cancelled"}""")
            return
        }
        if (scenario.startsWith("fixed-notices-")) {
            wire.chunk("before notices")
            wire.update("""{"sessionUpdate":"current_model_update","currentModelId":"gemini-pilot"}""")
            wire.update("""{"sessionUpdate":"config_option_update","configOptions":[{"id":"selected-model","currentValue":"gemini-pilot"}]}""")
            wire.update("""{"sessionUpdate":"config_option_update","configOptions":[{"category":"model","currentValue":"gemini-pilot"},{"id":"model","currentValue":"gemini-pilot"}]}""")
            wire.update("""{"sessionUpdate":"config_option_update","configOptions":[{"id":"unrelated"},{"id":"other-model-option","type":"select","currentValue":"gemini-other"},{"category":"mode","currentValue":"gemini-other"}]}""")
            wire.update("""{"sessionUpdate":"config_option_update","configOptions":[]}""")
            wire.update("""{"sessionUpdate":"current_model_update","currentModelId":"gemini-other"}""", session = "other-session")
            wire.update("""{"sessionUpdate":"current_model_update"}""", session = "other-session")
            wire.update("""{"sessionUpdate":"config_option_update","configOptions":[{"id":"selected-model","currentValue":"gemini-other"},{"category":"model"},{"id":"model"}]}""", session = "other-session")
            wire.update("""{"sessionUpdate":"config_option_update"}""", session = "other-session")
            wire.write(""""method":"session/update","params":{"update":{"sessionUpdate":"current_model_update","currentModelId":"gemini-other"}}""")
            wire.chunk("after notices")
            wire.result(promptId, """{"stopReason":"end_turn"}""")
            return
        }
        if (scenario.startsWith("current-model-")) {
            wire.chunk("current model")
            wire.result(promptId, """{"stopReason":"end_turn"}""")
            return
        }
        if (scenario == "stream") {
            stream(wire, fixture, promptId)
            return
        }

        wire.chunk("partial")
        when (scenario) {
            "cancel" -> {
                wire.permission("allow-and-deny", GOOD_OPTIONS)
                wire.expectCancel()
                wire.expectPermission("allow-and-deny", """{"outcome":"cancelled"}""")
                wire.chunk("discarded after cancel")
                Files.createFile(fixture.resolve("cancel-acknowledged"))
                wire.result(promptId, """{"stopReason":"cancelled"}""")
            }
            "hang" -> {
                wire.expectCancel()
                Files.createFile(fixture.resolve("cancel-received"))
                Thread.sleep(Long.MAX_VALUE)
            }
            "exit" -> exitProcess(7)
            "error" -> {
                System.err.println(PRIVATE_FIXTURE_DATA)
                wire.write(""""id":$promptId,"error":{"code":-32000,"message":"$PRIVATE_FIXTURE_DATA"}""")
            }
            else -> error("Unrecognized test scenario")
        }
    }

    private fun stream(wire: FakeWire, fixture: Path, promptId: JsonElement) {
        wire.chunk("foreign text", session = "other-session")
        wire.chunk("hidden thought", update = "agent_thought_chunk")
        wire.update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"image","data":"Zml4dHVyZQ==","mimeType":"image/png"}}""")
        wire.update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":10}}""")
        wire.chunk("第一段\n")
        while (!Files.exists(fixture.resolve("first-chunk"))) Thread.sleep(10)

        wire.permission("allow-and-deny", GOOD_OPTIONS)
        wire.expectPermission("allow-and-deny", """{"outcome":"selected","optionId":"deny"}""")
        wire.permission("allow-only", "[$ALLOW_OPTION]")
        wire.expectPermission("allow-only", """{"outcome":"cancelled"}""")
        wire.permission("ambiguous", "[$ALLOW_OPTION,{\"optionId\":\"allow\",\"name\":\"Deny\",\"kind\":\"reject_once\"}]")
        wire.expectPermission("ambiguous", """{"outcome":"cancelled"}""")
        wire.permission("foreign", GOOD_OPTIONS, session = "other-session")
        wire.expectPermission("foreign", """{"outcome":"cancelled"}""")

        listOf("fs/read_text_file", "terminal/create").forEachIndexed { index, method ->
            val id = 10 + index
            wire.write(""""id":$id,"method":"$method","params":{"sessionId":"fixture-session"}""")
            val reply = wire.read()
            check(reply["id"] == JsonPrimitive(id) && "result" !in reply)
            check(reply["error"]?.jsonObject?.get("code") == JsonPrimitive(-32601))
        }
        wire.chunk("第二段🌿")
        wire.result(promptId, """{"stopReason":"end_turn"}""")
    }
}

private class FakeWire(private val fixture: Path) {
    private val input = System.`in`.bufferedReader(Charsets.UTF_8)

    fun read(): JsonObject = json(checkNotNull(input.readLine())).also {
        check(it["jsonrpc"] == JsonPrimitive("2.0"))
        (it["method"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.let { method ->
            Files.writeString(fixture.resolve("methods"), method + "\n", CREATE, APPEND)
        }
    }

    fun request(method: String): JsonObject = read().also {
        check(it["method"] == JsonPrimitive(method) && "id" in it)
    }

    fun write(body: String) = println("""{"jsonrpc":"2.0",$body}""")

    fun result(id: JsonElement, body: String) = write(""""id":$id,"result":$body""")

    fun update(body: String, session: String = "fixture-session") = write(
        """"method":"session/update","params":{"sessionId":"$session","update":$body}""",
    )

    fun chunk(text: String, session: String = "fixture-session", update: String = "agent_message_chunk") = update(
        """{"sessionUpdate":"$update","content":{"type":"text","text":${JsonPrimitive(text)}}}""", session,
    )

    fun permission(id: String, options: String, session: String = "fixture-session") = write(
        """"id":"$id","method":"session/request_permission","params":{"sessionId":"$session","toolCall":{"toolCallId":"fixture-tool"},"options":$options}""",
    )

    fun expectPermission(id: String, outcome: String) {
        check(read() == json("""{"jsonrpc":"2.0","id":"$id","result":{"outcome":$outcome}}"""))
    }

    fun expectCancel() {
        check(read() == json("""{"jsonrpc":"2.0","method":"session/cancel","params":{"sessionId":"fixture-session"}}"""))
    }
}

private fun json(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

private const val FIXTURE_PROMPT = "Only reply with \"hello\".\n试点文本"
private const val PRIVATE_FIXTURE_DATA = "private-upstream-data"
private const val ALLOW_OPTION = """{"optionId":"allow","name":"Allow","kind":"allow_once"}"""
private const val GOOD_OPTIONS = """[$ALLOW_OPTION,{"optionId":"save-deny","name":"Always reject","kind":"reject_always"},{"optionId":"deny","name":"Reject","kind":"reject_once"}]"""
