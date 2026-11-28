package dev.soven.prismllm.antigravity

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.util.UUID
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(runBlocking { runAcpAcceptance(args) })
}

internal suspend fun runAcpAcceptance(
    args: Array<String>,
    writeReport: (JsonObject) -> Unit = { println(it) },
): Int = kotlinx.coroutines.withTimeout(150_000) {
        var stage = "SESSION_DISCOVERY"
        var realGenerationVerified = false
        try {
            require(args.size >= 6 && args[3] == "--") { "Invalid acceptance command" }
            val mode = args[0]
            require(mode in setOf("probe", "verify", "account-status"))
            val workspace = Path.of(args[1])
            val model = args[2]
            val command = args.drop(4)
            val pilot = AntigravityAcpPilot(
                command = command,
                workspace = workspace,
                environment = System.getenv().filterKeys { it == "AGY_PROXY_USERNAME" || it == "AGY_PROXY_PASSWORD" },
                agentWorkspace = Path.of("/workspace"),
                timeoutMs = 60_000,
                cancelGraceMs = 3_000,
            )
            val catalog = pilot.inspect()
            if (mode == "account-status") {
                if (model.isBlank() || model !in catalog.modelIds) {
                    throw AcpPilotException(AcpPilotException.Kind.MODEL_UNAVAILABLE)
                }
                if (catalog.currentModelId != model) throw AcpPilotException(AcpPilotException.Kind.MODEL_MISMATCH)
                writeReport(buildJsonObject {
                    put("status", "OFFICIAL_ACP_ACCOUNT_STATUS_VERIFIED")
                    put("modelId", model)
                    put("currentModelMatches", true)
                    put("generationRequests", 0)
                    put("modelSwitches", 0)
                    put("fallbacks", 0)
                    catalog.officialSubscriptionTier?.let { put("subscriptionTier", it) }
                    put("subscriptionEntitlementVerified", catalog.officialSubscriptionTier in setOf("PRO", "ULTRA", "PLUS", "AI_PREMIUM"))
                    put("subscriptionEvidence", if (catalog.officialSubscriptionTier == null)
                        "PLAN_NOT_EXPOSED_BY_OFFICIAL_ACP_SESSION" else "OFFICIAL_ACP_SESSION_TIER_METADATA")
                    put("realGenerationVerified", false)
                    put("refreshAfterExpiryVerified", false)
                    put("refreshEvidence", "NO_OFFICIAL_PRE_REFRESH_EXPIRY_METADATA")
                })
                return@withTimeout 0
            }
            if (mode == "probe") {
                writeReport(buildJsonObject {
                    put("status", "AUTHENTICATED_SESSION_AVAILABLE")
                    put("modelIds", buildJsonArray { catalog.modelIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                    catalog.currentModelId?.let { put("currentModelId", it) }
                    put("subscriptionEntitlementVerified", false)
                    put("realGenerationVerified", false)
                })
                return@withTimeout 0
            }
            if (model.isBlank() || model !in catalog.modelIds) {
                throw AcpPilotException(AcpPilotException.Kind.MODEL_UNAVAILABLE)
            }
            if (catalog.currentModelId != model) throw AcpPilotException(AcpPilotException.Kind.MODEL_MISMATCH)
            val marker = "PRISM-" + UUID.randomUUID().toString()
            val response = StringBuilder()
            var chunks = 0
            stage = "TEXT_GENERATION"
            val reason = pilot.prompt(
                "Reply with exactly $marker and nothing else. Do not use any tools.", modelId = model,
            ) { text ->
                require(response.length + text.length <= 16_384) { "Acceptance output exceeded its limit" }
                response.append(text)
                chunks += 1
            }
            acpServiceFailure(response.toString())?.let { throw AcpPilotException(it) }
            if (reason != AcpStopReason.END_TURN || response.toString().trim() != marker) {
                writeReport(buildJsonObject {
                    put("status", "UNEXPECTED_GENERATION_RESPONSE")
                    put("stage", stage)
                    put("modelId", model)
                    put("stopReason", reason.wireValue)
                    put("textChunks", chunks)
                    put("responseCharacters", response.length)
                    put("realGenerationVerified", false)
                    put("subscriptionEntitlementVerified", false)
                })
                return@withTimeout 1
            }
            realGenerationVerified = true
            stage = "CANCELLATION"
            val cancelled = verifyCancellation(pilot, model)
            if (!cancelled) {
                writeReport(buildJsonObject {
                    put("status", "CANCELLATION_NOT_ACKNOWLEDGED")
                    put("realGenerationVerified", true)
                    put("modelId", model)
                    put("textChunks", chunks)
                    put("cancelAcknowledged", false)
                    put("subscriptionEntitlementVerified", false)
                })
                return@withTimeout 1
            }
            stage = "CREDENTIAL_REUSE"
            val restarted = pilot.inspect()
            if (model !in restarted.modelIds) throw AcpPilotException(AcpPilotException.Kind.MODEL_UNAVAILABLE)
            if (restarted.currentModelId != model) throw AcpPilotException(AcpPilotException.Kind.MODEL_MISMATCH)
            writeReport(buildJsonObject {
                put("status", "REAL_ACP_ACCEPTANCE_PASSED")
                put("modelId", model)
                put("textChunks", chunks)
                put("realGenerationVerified", true)
                put("cancelAcknowledged", true)
                put("credentialsReused", true)
                put("subscriptionEntitlementVerified", false)
                put("refreshAfterExpiryVerified", false)
            })
            0
        } catch (failure: AcpPilotException) {
            val authRequired = failure.kind == AcpPilotException.Kind.AGENT_ERROR && failure.rpcCode == -32000 &&
                stage == "SESSION_DISCOVERY"
            writeReport(buildJsonObject {
                put("status", if (authRequired) "AUTHENTICATION_REQUIRED_OR_DENIED" else failure.kind.name)
                put("stage", stage)
                failure.rpcCode?.let { put("rpcCode", it) }
                put("realGenerationVerified", realGenerationVerified)
                put("subscriptionEntitlementVerified", false)
                put("refreshAfterExpiryVerified", false)
            })
            if (authRequired) 10 else 1
        } catch (_: IllegalArgumentException) {
            writeReport(buildJsonObject {
                put("status", "INVALID_ACCEPTANCE_INPUT_OR_OUTPUT")
                put("stage", stage)
                put("realGenerationVerified", realGenerationVerified)
            })
            1
        } catch (_: IllegalStateException) {
            writeReport(buildJsonObject {
                put("status", "ACCEPTANCE_ASSERTION_FAILED")
                put("stage", stage)
                put("realGenerationVerified", realGenerationVerified)
            })
            1
        }
}

private suspend fun verifyCancellation(pilot: AntigravityAcpPilot, model: String): Boolean = coroutineScope {
    val firstChunk = CompletableDeferred<Unit>()
    var acknowledged = false
    val turn = launch {
        try {
            pilot.prompt(
                "Print the numbers 1 to 100, one per line. Do not use any tools.",
                modelId = model,
                onCancelled = { acknowledged = it },
            ) {
                firstChunk.complete(Unit)
                awaitCancellation()
            }
        } finally {
            if (!firstChunk.isCompleted) firstChunk.completeExceptionally(IllegalStateException("No text received"))
        }
    }
    firstChunk.await()
    turn.cancelAndJoin()
    acknowledged
}
