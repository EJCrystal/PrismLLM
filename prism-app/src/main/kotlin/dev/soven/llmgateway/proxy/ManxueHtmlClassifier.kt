package dev.soven.llmgateway.proxy

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 满血AI 对 HTML 作品的分类结果。 */
data class ManxueAssessment(
    val quality: String,
    val label: String,
    val reason: String? = null,
    val source: String? = null,
)

/**
 * 调用满血AI 的公开 HTML 分类接口。
 *
 * 该接口只接收 HTML 源码，不会访问渠道上游，也不需要渠道密钥；
 * 请求中的 base_url 与 api_key 只是协议必填字段，HTML 模式不会使用它们。
 */
class ManxueHtmlClassifier(
    private val httpClient: HttpClient,
    baseUrl: String = "https://manxue.ai",
    private val timeoutMs: Long = 60_000,
    private val pollIntervalMs: Long = 1_500,
) {
    private val baseUrl = baseUrl.trimEnd('/')

    suspend fun classify(html: String): ManxueAssessment = try {
        withTimeout(timeoutMs) {
            val response = httpClient.post("$baseUrl/api/v1/tests") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(
                    TextContent(
                        buildJsonObject {
                            put("benchmark", "html")
                            put("base_url", "https://api.example.com/v1")
                            put("api_key", "not-used")
                            put("model", MANXUE_HTML_MODEL)
                            put("html", html)
                        }.toString(),
                        ContentType.Application.Json,
                    ),
                )
            }
            val body = response.bodyAsText()
            val json = parseJsonObject(body)
            if (response.status.value !in 200..299) {
                return@withTimeout failure("满血 API 返回 HTTP ${response.status.value}：${extractError(json, body)}")
            }
            if (json == null) {
                return@withTimeout failure("满血 API 返回了无法解析的响应")
            }
            if (json["assessment"] != null) {
                return@withTimeout parseAssessment(json)
            }
            val taskId = json["id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: return@withTimeout failure("满血 API 未返回任务 id")
            poll(taskId)
        }
    } catch (cause: TimeoutCancellationException) {
        failure("满血判分超时（${timeoutMs / 1000} 秒）")
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Throwable) {
        failure("无法连接满血 API：${cause.message?.take(200) ?: cause::class.simpleName}")
    }

    private suspend fun poll(taskId: String): ManxueAssessment {
        while (true) {
            delay(pollIntervalMs)
            val response = httpClient.get("$baseUrl/api/v1/tests/$taskId")
            val body = response.bodyAsText()
            val json = parseJsonObject(body)
            if (response.status.value !in 200..299) {
                return failure("满血 API 返回 HTTP ${response.status.value}：${extractError(json, body)}")
            }
            if (json == null) {
                return failure("满血 API 返回了无法解析的响应")
            }
            when (json["status"]?.jsonPrimitive?.contentOrNull) {
                "succeeded" -> return parseAssessment(json)
                "failed", "cancelled" -> {
                    val reason = extractError(json, "满血判分状态：${json["status"]?.jsonPrimitive?.contentOrNull}")
                    return failure("满血判分失败：$reason")
                }
            }
        }
    }

    private fun parseAssessment(json: JsonObject): ManxueAssessment {
        val assessment = json["assessment"] as? JsonObject
            ?: return failure("满血 API 未返回分类结果")
        val quality = assessment["quality"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it in QUALITY_VALUES }
            ?: "unknown"
        return ManxueAssessment(
            quality = quality,
            label = assessment["label"]?.jsonPrimitive?.contentOrNull ?: qualityLabel(quality),
            reason = assessment["reason"]?.jsonPrimitive?.contentOrNull,
            source = assessment["source"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun failure(reason: String) = ManxueAssessment(
        quality = "unknown",
        label = "无法判定",
        reason = reason.take(500),
    )

    private fun qualityLabel(quality: String): String = when (quality) {
        "normal" -> "正常"
        "degraded" -> "疑似降智"
        else -> "无法判定"
    }

    private fun parseJsonObject(body: String): JsonObject? =
        runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun extractError(json: JsonObject?, fallback: String): String {
        val error = json?.get("error")
        val message = when (error) {
            is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull
            is JsonPrimitive -> error.contentOrNull
            else -> null
        }
        return (message ?: fallback).trim().take(400)
    }

    private companion object {
        const val MANXUE_HTML_MODEL = "gpt-6-astra"
        val QUALITY_VALUES = setOf("normal", "degraded", "unknown")
    }
}
