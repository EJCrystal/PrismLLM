package dev.soven.prismllm.proxy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest
import java.util.UUID

/** Values shared by a request's configured headers and extraBody, across attempts and search rounds. */
data class UpstreamConversation(
    val sessionId: String,
    val threadId: String = sessionId,
    val subagentId: String = "",
) {
    fun expand(value: String, header: Boolean = false): String = PLACEHOLDERS.replace(value) { match ->
        val replacement = when (match.value) {
            "\${sessionId}" -> sessionId
            "\${threadId}" -> threadId
            else -> subagentId
        }
        if (header) replacement.filter { it in ' '..'~' } else replacement
    }

    fun expand(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (_, item) -> expand(item) })
        is JsonArray -> JsonArray(value.map(::expand))
        is JsonPrimitive -> if (value.isString) JsonPrimitive(expand(value.content)) else value
    }

    companion object {
        private val PLACEHOLDERS = Regex("\\$\\{(?:sessionId|threadId|subagentId)}")
        private val SESSION_HEADERS = listOf(
            "x-opencode-session", "x-session-affinity", "x-session-id", "session_id", "session-id",
            "x-claude-code-session-id", "x-conversation-id", "conversation-id", "x-deepseek-harness-session-id",
        )

        fun own(prefix: String = "prismllm-"): UpstreamConversation = UpstreamConversation(prefix + UUID.randomUUID())

        /** Like Magpie, prefer the agent's session; hash the first user turn only when none is named. */
        fun fromRequest(header: (String) -> String?, body: JsonObject, callerId: String): UpstreamConversation {
            fun named(name: String) = header(name)?.trim()?.takeIf(String::isNotEmpty)?.take(128)
            val session = SESSION_HEADERS.firstNotNullOfOrNull(::named) ?: run {
                val opening = firstUserTurn(body) ?: return@run own().sessionId
                // A shared gateway must not merge different callers with identical opening messages.
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(callerId.toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
                val hash = digest.digest(opening.toString().toByteArray(Charsets.UTF_8))
                "prismllm-" + hash.take(12).joinToString("") { "%02x".format(it) }
            }
            val clientMetadata = body["client_metadata"] as? JsonObject
            val turn = header("x-codex-turn-metadata")
                ?: (clientMetadata?.get("x-codex-turn-metadata") as? JsonPrimitive)?.contentOrNull
            val metadata = turn?.takeIf { it.length <= 8192 }?.let {
                runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull()
            }
            val thread = (metadata?.get("thread_id") as? JsonPrimitive)
                ?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.length in 1..128 }
                ?: named("thread-id") ?: session
            return UpstreamConversation(session, thread, named("x-claude-code-agent-id").orEmpty())
        }

        private fun firstUserTurn(body: JsonObject): JsonElement? {
            val turns = body["messages"] as? JsonArray ?: body["contents"] as? JsonArray
                ?: body["input"] as? JsonArray
            if (turns != null) {
                return turns.filterIsInstance<JsonObject>().firstOrNull {
                    (it["role"] as? JsonPrimitive)?.contentOrNull == "user" ||
                        (body["contents"] is JsonArray && "role" !in it)
                }
            }
            return (body["input"] as? JsonPrimitive)?.takeIf { it.isString }
        }
    }
}
