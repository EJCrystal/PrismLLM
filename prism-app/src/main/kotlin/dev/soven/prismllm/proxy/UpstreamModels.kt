package dev.soven.prismllm.proxy

import dev.soven.prismllm.shared.error.GatewayError
import dev.soven.prismllm.channel.domain.ChannelRecord
import dev.soven.prismllm.shared.domain.UpstreamCredential
import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.channel.domain.authTypeFor
import dev.soven.prismllm.shared.domain.UpstreamRequestOverrides
import dev.soven.prismllm.domain.UpstreamModel
import dev.soven.prismllm.channel.domain.UpstreamHeaderProfile
import dev.soven.prismllm.shared.domain.KeyCredentialResolver
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
    credentialRef: UpstreamCredential,
    credentials: KeyCredentialResolver,
    httpClient: HttpClient,
    authorizedOnly: Boolean = true,
    globalOverrides: UpstreamRequestOverrides = UpstreamRequestOverrides(),
    headerProfiles: List<UpstreamHeaderProfile> = UpstreamHeaderProfiles.defaults,
): List<UpstreamModel> {
    if (authorizedOnly && credentialRef.models.isEmpty() && !credentialRef.isOAuth) return emptyList()
    val credential = resolveSavedCredential(credentials, credentialRef)

    val protocol = channel.modelListProtocol()
    val models = fetchUpstreamModels(
        channel.baseUrl, credential, httpClient, channel.authTypeFor(protocol),
        protocol = protocol, globalOverrides = globalOverrides, channelOverrides = channel.requestOverrides,
        headerProfiles = headerProfiles,
    )
    return if (authorizedOnly) models.filter { credentialRef.supports(it.id) } else models
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
    headerProfiles: List<UpstreamHeaderProfile> = UpstreamHeaderProfiles.defaults,
): List<UpstreamModel> {
    val url = baseUrl.trimEnd('/') + "/models"
    val policy = UpstreamRequestPolicy(globalOverrides, channelOverrides, protocol, authType, headerProfiles, baseUrl)
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
                        throw GatewayError(
                            HttpStatusCode.BadGateway, "upstream_models_error", message, "api_error",
                        )
                    }
                    val bytes = response.bodyAsChannel().readRemaining(remainingBytes.toLong() + 1).readBytes()
                    remainingBytes -= bytes.size
                    if (remainingBytes < 0) {
                        throw GatewayError(
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
                    throw GatewayError(
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
    } catch (e: GatewayError) {
        throw e
    } catch (e: TimeoutCancellationException) {
        throw GatewayError(
            HttpStatusCode.GatewayTimeout,
            "upstream_models_timeout",
            "拉取上游模型列表超时",
            "timeout_error",
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw GatewayError(
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
    } ?: throw GatewayError(
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
