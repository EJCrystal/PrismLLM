package dev.soven.prismllm.proxy.codeassist

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
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun read(accessToken: String, project: String, proxy: InetSocketAddress? = null): OAuthQuotaSnapshot? {
        val body = buildJsonObject { put("project", project) }
        val response = clients.client(proxy).post(baseUrl + "/v1internal:retrieveUserQuotaSummary") {
            header(HttpHeaders.Authorization, "Bearer " + accessToken)
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
        for (group in groups) {
            val buckets = (group as? JsonObject)?.get("buckets") as? JsonArray ?: continue
            for (bucket in buckets) {
                val item = bucket as? JsonObject ?: continue
                if ((item["disabled"] as? JsonPrimitive)?.booleanOrNull == true) continue
                val window = windowOf(item) ?: continue
                when ((item["window"] as? JsonPrimitive)?.contentOrNull?.lowercase()) {
                    "5h" -> if (primary == null) primary = window
                    "weekly" -> if (secondary == null) secondary = window
                }
            }
        }
        if (primary == null && secondary == null) return null
        return OAuthQuotaSnapshot(primary = primary, secondary = secondary, checkedAt = checkedAt)
    }

    private fun windowOf(bucket: JsonObject): OAuthQuotaWindow? {
        val remaining = (bucket["remainingFraction"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val reset = (bucket["resetTime"] as? JsonPrimitive)?.contentOrNull
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        return OAuthQuotaWindow(
            usedPercent = ((1.0 - remaining) * 100.0).coerceIn(0.0, 100.0),
            resetsAt = reset,
        )
    }
}
