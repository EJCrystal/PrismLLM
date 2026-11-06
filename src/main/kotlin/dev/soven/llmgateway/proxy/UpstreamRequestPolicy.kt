package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.api.GatewayException
import dev.soven.llmgateway.api.badRequest
import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.domain.UpstreamProtocolOverrides
import dev.soven.llmgateway.domain.UpstreamHeaderProfile
import dev.soven.llmgateway.domain.UpstreamRequestOverrides
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Bundled header templates and protocol-based profile selection. */
object UpstreamHeaderProfiles {
    val defaults = listOf(
        UpstreamHeaderProfile(
            "claude", "Claude Code", "2.1.312", linkedMapOf(
                "User-Agent" to "claude-cli/2.1.312 (external, cli)",
                "x-app" to "cli",
                "x-stainless-lang" to "js",
                "x-stainless-package-version" to "0.94.0",
                "x-stainless-runtime" to "node",
                "x-stainless-retry-count" to "0",
            ), "npm:@anthropic-ai/claude-code",
        ),
        UpstreamHeaderProfile(
            "codex", "Codex", "0.165.0", linkedMapOf(
                "User-Agent" to "codex_cli_rs/0.165.0 (Linux; x86_64)",
                "originator" to "codex_cli_rs",
                "version" to "0.165.0",
            ), "github:openai/codex",
        ),
        UpstreamHeaderProfile(
            "google", "Google Gen AI SDK", "2.32.0", linkedMapOf(
                "User-Agent" to "google-genai-sdk/2.32.0",
                "x-goog-api-client" to "google-genai-sdk/2.32.0",
            ), "npm:@google/genai",
        ),
        UpstreamHeaderProfile(
            "antigravity", "Antigravity", "2.23.0", linkedMapOf(
                "User-Agent" to "antigravity/hub/2.23.0 windows/amd64",
            ), "builtin",
        ),
    )
    val ids = defaults.map { it.id }.toSet() + setOf("auto", "none")

    fun headers(
        profile: String,
        protocol: GatewayProtocol?,
        authType: String,
        profiles: List<UpstreamHeaderProfile> = defaults,
    ): Map<String, String> {
        val selected = if (profile == "auto") {
            when (protocol) {
                GatewayProtocol.MESSAGES -> "claude"
                GatewayProtocol.GEMINI -> "google"
                GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES -> "codex"
                null -> when (authType) {
                    "anthropic" -> "claude"
                    "google" -> "google"
                    else -> "codex"
                }
            }
        } else profile
        return profiles.firstOrNull { it.id == selected }?.headers.orEmpty()
    }
}

/** A per-request snapshot reused for every attempt; channel layers never mutate the original body. */
class UpstreamRequestPolicy(
    global: UpstreamRequestOverrides,
    channel: UpstreamRequestOverrides,
    private val protocol: GatewayProtocol?,
    private val authType: String,
    private val profiles: List<UpstreamHeaderProfile> = UpstreamHeaderProfiles.defaults,
) {
    private val layers: List<UpstreamProtocolOverrides> = buildList {
        if (channel.inheritGlobal) {
            add(global.common())
            global.protocols[protocol?.value]?.let(::add)
        }
        add(channel.common())
        channel.protocols[protocol?.value]?.let(::add)
    }
    private val profile = layers.asReversed().firstNotNullOfOrNull { it.headerProfile } ?: "auto"
    private val reasoningContentPolicy: ReasoningContentPolicy =
        channel.reasoningContentPolicy?.let(ReasoningContentPolicy::fromValue)
            ?: (if (channel.inheritGlobal) global.reasoningContentPolicy?.let(ReasoningContentPolicy::fromValue) else null)
            ?: ReasoningContentPolicy.AUTO

    fun applyHeaders(builder: HttpRequestBuilder) {
        val result = linkedMapOf<String, String?>()
        val selectedHeaders = UpstreamHeaderProfiles.headers(profile, protocol, authType, profiles)
        selectedHeaders.forEach { (name, value) ->
            result[name.lowercase()] = value
        }
        layers.forEach { layer ->
            layer.userAgent?.let { result["user-agent"] = it.takeIf(String::isNotEmpty) }
            layer.headers.forEach { (name, value) -> result[name.lowercase()] = value }
        }
        if (selectedHeaders.keys.any { it.equals("originator", ignoreCase = true) }) {
            val identity = CODEX_USER_AGENT.find(result["user-agent"].orEmpty())
            if (identity != null) {
                val explicit = layers.flatMap { it.headers.keys }.map(String::lowercase).toSet()
                if ("originator" !in explicit) result["originator"] = identity.groupValues[1]
                if ("version" !in explicit) result["version"] = identity.groupValues[2]
            }
        }
        result.forEach { (name, value) ->
            builder.headers.remove(name)
            // An explicit empty UA prevents the Java engine from supplying its own default identity.
            if (name == "user-agent" && value == null) builder.headers.append(name, "")
            else if (value != null) builder.headers.append(name, value)
        }
    }

    fun body(original: JsonObject, upstreamModel: String, limitBytes: Long = Long.MAX_VALUE): JsonObject {
        val routed = if (protocol == GatewayProtocol.GEMINI) JsonObject(original - "model" - "stream")
            else JsonObject(original + ("model" to JsonPrimitive(upstreamModel)))
        val merged = layers.fold(routed) { current, layer -> mergePatch(current, layer.body) }
        val result = if (protocol == GatewayProtocol.CHAT_COMPLETIONS) {
            val withoutMaxTokens = if ("max_completion_tokens" in merged) {
                JsonObject(merged - "max_tokens")
            } else {
                merged
            }
            ChatReasoningContentNormalizer.normalize(withoutMaxTokens, upstreamModel, reasoningContentPolicy)
        } else {
            merged
        }
        if (result.toString().toByteArray(Charsets.UTF_8).size.toLong() > limitBytes) {
            throw GatewayException(
                HttpStatusCode.PayloadTooLarge, "request_too_large",
                "改写后的上游请求体超过 $limitBytes 字节", "invalid_request_error",
            )
        }
        return result
    }
}

/** RFC 7396: objects merge recursively; arrays/scalars replace; null deletes an object member. */
internal fun mergePatch(original: JsonObject, patch: JsonObject): JsonObject {
    val result = original.toMutableMap()
    patch.forEach { (name, value) ->
        when (value) {
            JsonNull -> result.remove(name)
            is JsonObject -> result[name] = mergePatch(result[name] as? JsonObject ?: JsonObject(emptyMap()), value)
            else -> result[name] = value
        }
    }
    return JsonObject(result)
}

/** Reject invalid rules when saving, before they can break routing or produce illegal HTTP headers. */
fun validateUpstreamRequestOverrides(overrides: UpstreamRequestOverrides) {
    fun invalid(message: String): Nothing = throw badRequest("invalid_request_overrides", message)
    if (overrides.protocols.keys.any { GatewayProtocol.fromValue(it) == null }) {
        invalid("按协议覆盖的键必须是 chat_completions、responses、messages 或 gemini")
    }
    if (overrides.reasoningContentPolicy != null &&
        ReasoningContentPolicy.fromValue(overrides.reasoningContentPolicy) == null
    ) {
        invalid("reasoning_content 处理策略必须是 auto、always 或 never")
    }
    val layers = listOf(overrides.common()) + overrides.protocols.values
    layers.forEach { layer ->
        if (layer.headerProfile != null && layer.headerProfile !in UpstreamHeaderProfiles.ids) {
            invalid("请求头预设无效")
        }
        if (layer.userAgent != null && !validHeaderValue(layer.userAgent)) invalid("User-Agent 包含非法字符或超过 8192 字符")
        if (layer.headers.size > 64) invalid("每层自定义请求头不能超过 64 个")
        val names = mutableSetOf<String>()
        layer.headers.forEach { (name, value) ->
            if (name.length !in 1..128 || !HEADER_NAME.matches(name)) invalid("请求头名称无效")
            val lower = name.lowercase()
            if (!names.add(lower)) invalid("请求头名称不能重复（不区分大小写）")
            if (lower in MANAGED_HEADERS) invalid("请求头 $name 由网关管理，不能通过请求覆盖修改")
            if (value != null && !validHeaderValue(value)) invalid("请求头 $name 包含非法字符或超过 8192 字符")
        }
        if (layer.body.keys.any { it == "model" || it == "stream" }) {
            invalid("请求体覆盖不能修改 model 或 stream；模型映射和流式模式由网关管理")
        }
        if (jsonDepth(layer.body) > 32) invalid("请求体覆盖嵌套不能超过 32 层")
    }
    if (Json.encodeToString(overrides).toByteArray(Charsets.UTF_8).size > 65_536) invalid("请求覆盖配置不能超过 64KB")
}

internal fun validHeaderValue(value: String): Boolean = value.length <= 8192 &&
    value.all { it == '\t' || it in ' '..'~' }

private fun jsonDepth(value: JsonElement, depth: Int = 0): Int = when {
    depth > 32 -> depth
    value is JsonObject -> value.values.maxOfOrNull { jsonDepth(it, depth + 1) } ?: depth + 1
    value is JsonArray -> value.maxOfOrNull { jsonDepth(it, depth + 1) } ?: depth + 1
    else -> depth
}

private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
private val CODEX_USER_AGENT = Regex("^(codex_cli_rs|codex_vscode|codex-tui)/([0-9]+(?:\\.[0-9]+){1,3}(?:-[0-9A-Za-z.]+)?)(?:\\s|$)")
internal val MANAGED_HEADERS = setOf(
    "authorization", "proxy-authorization", "x-api-key", "x-goog-api-key", "cookie", "set-cookie",
    "host", "content-length", "content-type", "content-encoding", "transfer-encoding", "connection", "te", "trailer",
    "accept-encoding", "upgrade", "expect", "traceparent",
)
