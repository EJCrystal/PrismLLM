package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.api.GatewayException
import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.domain.UpstreamModel
import dev.soven.llmgateway.security.CredentialCipher
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 拉取 OpenAI 兼容上游的模型列表。渠道 baseUrl 已包含版本路径时，请求会落到 `/v1/models`。
 *
 * @author 文杰
 * @since 2026-10-22
 */
suspend fun fetchUpstreamModels(
    channel: ChannelRecord,
    credentialCipher: CredentialCipher,
    httpClient: HttpClient,
): List<UpstreamModel> {
    val credential = runCatching {
        credentialCipher.decrypt(channel.credentialCiphertext, channel.credentialNonce)
    }.getOrElse {
        throw GatewayException(
            HttpStatusCode.BadGateway,
            "upstream_credential_invalid",
            "渠道凭据无法解密",
            retryable = false,
        )
    }

    val url = channel.baseUrl.trimEnd('/') + "/models"
    try {
        return withTimeout(MODEL_LIST_TIMEOUT_MS) {
            val response = httpClient.get(url) {
                header(HttpHeaders.Authorization, "Bearer $credential")
                header(HttpHeaders.Accept, "application/json")
            }
            if (!response.status.isSuccess()) {
                val message = when (response.status.value) {
                    401, 403 -> "上游凭据无效或无权访问模型列表"
                    404 -> "上游没有提供 /models 接口"
                    else -> "上游模型列表返回错误 ${response.status.value}"
                }
                throw GatewayException(
                    HttpStatusCode.BadGateway,
                    "upstream_models_error",
                    message,
                    "api_error",
                )
            }
            parseModelList(response.bodyAsText())
        }
    } catch (e: GatewayException) {
        throw e
    } catch (e: TimeoutCancellationException) {
        throw GatewayException(
            HttpStatusCode.GatewayTimeout,
            "upstream_models_timeout",
            "拉取上游模型列表超时",
            "timeout_error",
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw GatewayException(
            HttpStatusCode.BadGateway,
            "upstream_models_unreachable",
            "无法连接上游模型列表",
            "api_error",
        )
    }
}

internal fun parseModelList(text: String): List<UpstreamModel> {
    val root = runCatching { Json.parseToJsonElement(text) }.getOrNull()
    val array = when (root) {
        is JsonArray -> root
        is JsonObject -> (root["data"] as? JsonArray)
            ?: (root["models"] as? JsonArray)
            ?: ((root["data"] as? JsonObject)?.get("models") as? JsonArray)
        else -> null
    } ?: throw GatewayException(
        HttpStatusCode.BadGateway,
        "upstream_models_invalid",
        "上游返回的模型列表格式无法识别",
        "api_error",
    )

    return array.mapNotNull { item ->
        when (item) {
            is JsonPrimitive -> item.contentOrNull?.takeIf { it.isNotBlank() }?.let { UpstreamModel(it) }
            is JsonObject -> {
                val id = (item["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: (item["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                id?.let {
                    UpstreamModel(
                        id = it,
                        ownedBy = (item["owned_by"] as? JsonPrimitive)?.contentOrNull,
                    )
                }
            }
            else -> null
        }
    }.distinctBy { it.id }.sortedBy { it.id }
}

private const val MODEL_LIST_TIMEOUT_MS = 15_000L
