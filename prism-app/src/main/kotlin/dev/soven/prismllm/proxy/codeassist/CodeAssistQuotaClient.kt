package dev.soven.prismllm.proxy.codeassist

import dev.soven.prismllm.oauth.domain.OAuthQuotaGroup
import dev.soven.prismllm.oauth.domain.OAuthQuotaSnapshot
import dev.soven.prismllm.oauth.domain.OAuthQuotaWindow
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.time.Instant

/**
 * Antigravity 的额度读数：retrieveUserQuotaSummary 的分组桶里，5h 与 weekly
 * 两个窗口分别对应我们快照的 primary/secondary。
 * 对应 magpie 的 retrieveUserQuotaSummary（google.go）。
 *
 * @author 文杰
 * @since 2026-11-06
 */
class CodeAssistQuotaClient(
    private val httpClient: HttpClient,
    private val baseUrl: String = "https://daily-cloudcode-pa.googleapis.com",
    /** 账号代理：额度读取与 token 刷新走同一个出口。 */
    private val clients: CodeAssistClientPool = CodeAssistClientPool(httpClient),
    /** 当前 Antigravity 客户端 UA；由全局请求头目录提供，目录不可用时退回内置值。 */
    private val userAgent: () -> String = { DEFAULT_ANTIGRAVITY_CODE_ASSIST_USER_AGENT },
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun read(accessToken: String, project: String, proxy: InetSocketAddress? = null): OAuthQuotaSnapshot? {
        val body = buildJsonObject { put("project", project) }
        val response = clients.client(proxy).post(baseUrl + "/v1internal:retrieveUserQuotaSummary") {
            header(HttpHeaders.Authorization, "Bearer " + accessToken)
            header(HttpHeaders.UserAgent, userAgent())
            header("X-Goog-Api-Client", "gl-node/22.21.1")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) return null
        return parse(text, System.currentTimeMillis())
    }

    internal fun parse(text: String, checkedAt: Long): OAuthQuotaSnapshot? {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val groups = root["groups"] as? JsonArray ?: return null
        var primary: OAuthQuotaWindow? = null
        var secondary: OAuthQuotaWindow? = null
        val parsedGroups = mutableListOf<OAuthQuotaGroup>()
        for (group in groups) {
            val groupObject = group as? JsonObject ?: continue
            val buckets = groupObject["buckets"] as? JsonArray ?: continue
            val windows = mutableListOf<OAuthQuotaWindow>()
            for (bucket in buckets) {
                val item = bucket as? JsonObject ?: continue
                if ((item["disabled"] as? JsonPrimitive)?.booleanOrNull == true) continue
                val key = (item["window"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: continue
                val window = windowOf(item, windowSecondsOf(key)) ?: continue
                windows += window
                when (key) {
                    "5h" -> if (primary == null) primary = window
                    "weekly" -> if (secondary == null) secondary = window
                }
            }
            if (windows.isNotEmpty()) {
                parsedGroups += OAuthQuotaGroup(groupName(groupObject), windows)
            }
        }
        if (primary == null && secondary == null) return null
        return OAuthQuotaSnapshot(
            primary = primary,
            secondary = secondary,
            groups = parsedGroups,
            checkedAt = checkedAt,
        )
    }

    /** 上游只给 "5h" / "weekly" 这类窗口名，转成秒数后前端才能统一格式化。 */
    private fun windowSecondsOf(key: String): Int? = when (key) {
        "5h" -> 18_000
        "weekly" -> 604_800
        else -> null
    }

    private fun groupName(group: JsonObject): String {
        val raw = (group["displayName"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            ?: (group["name"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            ?: return "额度"
        return normalizeQuotaGroupName(raw)
    }

    /** 上游把分组叫 "Gemini Models" / "Claude and GPT Models"；去掉通用后缀，便于界面展示。 */
    internal fun normalizeQuotaGroupName(raw: String): String {
        val trimmed = raw.trim()
        val name = if (trimmed.endsWith(MODELS_SUFFIX, ignoreCase = true)) {
            trimmed.dropLast(MODELS_SUFFIX.length).trim()
        } else {
            trimmed
        }
        if (name.isEmpty()) return "额度"
        return name.replace(" and ", " & ")
    }

    private fun windowOf(bucket: JsonObject, windowSeconds: Int?): OAuthQuotaWindow? {
        val remaining = (bucket["remainingFraction"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val reset = (bucket["resetTime"] as? JsonPrimitive)?.contentOrNull
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        return OAuthQuotaWindow(
            usedPercent = ((1.0 - remaining) * 100.0).coerceIn(0.0, 100.0),
            windowSeconds = windowSeconds,
            resetsAt = reset,
        )
    }
}

/** 上游额度分组名的通用后缀，例如 "Gemini Models"。 */
private const val MODELS_SUFFIX = " models"
