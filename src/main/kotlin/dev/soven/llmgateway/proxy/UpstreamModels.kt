package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.api.GatewayException
import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.domain.ChannelKeyRecord
import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.domain.UpstreamRequestOverrides
import dev.soven.llmgateway.domain.UpstreamModel
import dev.soven.llmgateway.security.CredentialCipher
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 拉取上游模型列表。baseUrl 必须包含上游要求的版本路径、不含端点；统一追加 `/models`。
 * Gemini 使用 nextPageToken 拉取全部页面，所有页面共享超时与响应大小预算。
 *
 * @author 文杰
 * @since 2026-10-22
 */
suspend fun fetchUpstreamModels(
    channel: ChannelRecord,
    key: ChannelKeyRecord,
    credentialCipher: CredentialCipher,
    httpClient: HttpClient,
    authorizedOnly: Boolean = true,
    globalOverrides: UpstreamRequestOverrides = UpstreamRequestOverrides(),
): List<UpstreamModel> {
    if (authorizedOnly && key.models.isEmpty()) return emptyList()
    val credential = runCatching {
        credentialCipher.decrypt(key.credentialCiphertext, key.credentialNonce)
    }.getOrElse {
        throw GatewayException(
            HttpStatusCode.BadGateway,
            "upstream_credential_invalid",
            "渠道凭据无法解密",
            retryable = false,
        )
    }

    val models = fetchUpstreamModels(
        channel.baseUrl, credential, httpClient, channel.authType,
        protocol = channel.modelListProtocol(), globalOverrides = globalOverrides, channelOverrides = channel.requestOverrides,
    )
    return if (authorizedOnly) models.filter { it.id in key.models } else models
}

/**
 * 使用尚未保存的渠道地址和凭据发现上游模型，供新增渠道时预选授权模型。
 */
suspend fun fetchUpstreamModels(
    baseUrl: String,
    credential: String,
    httpClient: HttpClient,
    authType: String = "bearer",
    protocol: GatewayProtocol? = null,
    globalOverrides: UpstreamRequestOverrides = UpstreamRequestOverrides(),
    channelOverrides: UpstreamRequestOverrides = UpstreamRequestOverrides(),
): List<UpstreamModel> {
    val url = baseUrl.trimEnd('/') + "/models"
    val policy = UpstreamRequestPolicy(globalOverrides, channelOverrides, protocol, authType)
    try {
        return withTimeout(MODEL_LIST_TIMEOUT_MS) {
            val models = mutableListOf<UpstreamModel>()
            val seenPageTokens = mutableSetOf<String>()
            var pageToken: String? = null
            var remainingBytes = MODEL_LIST_MAX_BYTES
            do {
                pageToken = httpClient.prepareGet(url) {
                    applyUpstreamAuthentication(credential, authType, protocol)
                    header(HttpHeaders.Accept, "application/json")
                    policy.applyHeaders(this)
                    if (protocol == GatewayProtocol.GEMINI) {
                        parameter("pageSize", 1000)
                        pageToken?.let { parameter("pageToken", it) }
                    }
                }.execute { response ->
                    if (!response.status.isSuccess()) {
                        val message = when (response.status.value) {
                            401, 403 -> "上游凭据无效或无权访问模型列表"
                            404 -> "上游没有提供 /models 接口"
                            else -> "上游模型列表返回错误 ${response.status.value}"
                        }
                        throw GatewayException(
                            HttpStatusCode.BadGateway, "upstream_models_error", message, "api_error",
                        )
                    }
                    val bytes = response.bodyAsChannel().readRemaining(remainingBytes.toLong() + 1).readBytes()
                    remainingBytes -= bytes.size
                    if (remainingBytes < 0) {
                        throw GatewayException(
                            HttpStatusCode.BadGateway,
                            "upstream_models_too_large",
                            "上游模型列表超过 2MB",
                            "api_error",
                        )
                    }
                    val root = runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) }.getOrNull()
                    models += parseModelList(root)
                    if (protocol == GatewayProtocol.GEMINI) {
                        ((root as? JsonObject)?.get("nextPageToken") as? JsonPrimitive)
                            ?.contentOrNull?.takeIf { it.isNotBlank() }
                    } else {
                        null
                    }
                }
                if (pageToken != null && !seenPageTokens.add(pageToken)) {
                    throw GatewayException(
                        HttpStatusCode.BadGateway,
                        "upstream_models_invalid",
                        "上游模型列表返回重复分页标记",
                        "api_error",
                    )
                }
            } while (pageToken != null)
            models.map { model ->
                if (protocol == GatewayProtocol.GEMINI) model.copy(id = model.id.removePrefix("models/")) else model
            }.distinctBy { it.id }.sortedBy { it.id }
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

internal fun parseModelList(text: String): List<UpstreamModel> =
    parseModelList(runCatching { Json.parseToJsonElement(text) }.getOrNull())

private fun parseModelList(root: JsonElement?): List<UpstreamModel> {
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
private const val MODEL_LIST_MAX_BYTES = 2 * 1024 * 1024
