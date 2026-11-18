package dev.soven.prismllm.api

import dev.soven.prismllm.channel.domain.ProviderEndpoints
import dev.soven.prismllm.channel.domain.UpstreamEnvelope
import dev.soven.prismllm.channel.domain.upstreamEnvelope
import dev.soven.prismllm.oauth.infra.OAuthRefreshException
import dev.soven.prismllm.oauth.infra.proxyAddress
import dev.soven.prismllm.plugin.host.PluginExecution
import dev.soven.prismllm.plugin.host.PluginHostException
import dev.soven.prismllm.plugin.host.PluginUiContribution
import dev.soven.prismllm.plugin.sdk.DiagnosticInput
import dev.soven.prismllm.plugin.sdk.DiagnosticTarget
import dev.soven.prismllm.plugin.sdk.ProbeOutcome
import dev.soven.prismllm.plugin.sdk.ProbePlan
import dev.soven.prismllm.proxy.ModelConnectivityTestResult
import dev.soven.prismllm.proxy.testChannelModelConnectivity
import dev.soven.prismllm.proxy.testOAuthAccountModelConnectivity
import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.shared.error.GatewayError
import dev.soven.prismllm.shared.error.badRequest
import dev.soven.prismllm.shared.error.notFound
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

internal const val CONNECTIVITY_PLUGIN_ID = "prism.connectivity"

/** 只用于旧接口桥接；插件的选择与 UI 贡献本身由运行时描述决定。 */
internal suspend fun GatewayApplication.requirePluginEntry(
    id: String,
    kind: String? = null,
    actionId: String? = null,
): PluginUiContribution {
    val entries = pluginRuntime.contributions().filter { it.pluginId == id && (kind == null || kind in it.entry.contexts) }
    if (entries.isEmpty()) throw PluginHostException("plugin_unavailable", "诊断插件尚未生效或已停用，请在插件页面检查安装状态", 503)
    return if (actionId == null) entries.first() else entries.firstOrNull { it.entry.action.id == actionId }
        ?: throw badRequest("invalid_plugin_input", "插件动作无效")
}

internal suspend fun GatewayApplication.executeConnectivityPlugin(
    context: PluginInvocationContext,
    request: ChannelModelTestRequest,
): PluginExecution {
    val entry = requirePluginEntry(CONNECTIVITY_PLUGIN_ID, context.kind)
    if (request.prompt?.trim()?.length?.let { it > 4_000 } == true) {
        throw badRequest("invalid_model_test_prompt", "测试提示词不能超过 4000 个字符")
    }
    val values = buildJsonObject {
        put("model", request.model)
        put("protocol", request.protocol)
        request.keyId?.takeIf { context.kind == "channel" }?.let { put("keyId", it) }
        request.prompt?.let { put("prompt", it) }
        if (entry.entry.action.fields.any { it.key == "scope" }) put("scope", "single")
    }
    return executePluginProbe(CONNECTIVITY_PLUGIN_ID, entry.activeVersion, context, DiagnosticInput(entry.entry.action.id, values))
}

internal suspend fun GatewayApplication.executePluginProbe(
    id: String,
    expectedActiveVersion: String,
    context: PluginInvocationContext,
    input: DiagnosticInput,
): PluginExecution {
    // 在任何凭据准备或 Provider 请求前确认准入；执行器在真正发送前还会复核。
    if (context.kind !in dev.soven.prismllm.plugin.sdk.PluginApi.contexts) {
        throw badRequest("invalid_plugin_context", "插件目标类型无效")
    }
    val entry = requirePluginEntry(id, context.kind, input.actionId)
    val model = input.values.stringValue("model")?.trim().orEmpty()
    if (model.isEmpty() || model.length > 200) throw badRequest("invalid_model_test", "模型名长度必须在 1..200 之间")
    val protocol = GatewayProtocol.fromValue(input.values.stringValue("protocol")?.trim().orEmpty())
        ?: throw badRequest("invalid_model_test_protocol", "测试协议无效")
    if (context.id.isBlank() || context.id.length > 200) throw badRequest("invalid_plugin_context", "插件目标 id 无效")
    return when (context.kind) {
        "channel" -> {
            val channel = channelRepository.find(context.id)?.let { channelWithCredentials(this, it) }
                ?: throw notFound("channel_not_found", "渠道不存在")
            if (model !in channel.models) throw badRequest("model_not_authorized", "渠道「${channel.name}」尚未授权模型「$model」")
            if (protocol !in channel.protocols) throw badRequest("model_test_protocol_unsupported", "渠道未启用协议 ${protocol.value}")
            val credential = resolveChannelCredentialForModel(channel, model, input.values.stringValue("keyId"))
            val target = DiagnosticTarget("channel", channel.id, model, protocol.value, channel.name, credential.id, credential.name)
            val credentialBinding = if (entry.entry.action.fields.any { it.kind == "credential" && it.key == "keyId" }) {
                mapOf("keyId" to JsonPrimitive(credential.id))
            } else emptyMap()
            val normalized = DiagnosticInput(input.actionId, JsonObject(input.values + mapOf(
                "model" to JsonPrimitive(model), "protocol" to JsonPrimitive(protocol.value),
            ) + credentialBinding))
            pluginRuntime.execute(id, expectedActiveVersion, normalized, target) { plan ->
                testChannelModelConnectivity(
                    channel, credential, model, protocol, plan.prompt, oauthTokenService, httpClient,
                    timeoutMs = plan.timeoutMs, maxOutputTokens = plan.maxOutputTokens,
                    globalOverrides = upstreamRequestSettingsRepository.load().overrides,
                    headerProfiles = upstreamHeaderCatalogService.profiles(),
                ).toPluginOutcome()
            }
        }
        "oauth-account" -> {
            // findDetailed 的模型授权不能省略，否则空列表会被解释为全部模型。
            val account = oauthAccountRepository.findDetailed(context.id)
                ?: throw notFound("oauth_account_not_found", "订阅账号不存在")
            if (!account.supports(model)) throw badRequest("model_not_authorized", "账号「${account.name}」未授权模型「$model」")
            if (protocol !in ProviderEndpoints.of(account.provider).protocols) {
                throw badRequest("model_test_protocol_unsupported", "订阅账号不支持协议 ${protocol.value}")
            }
            val target = DiagnosticTarget("oauth-account", account.id, model, protocol.value, account.name, account.id, account.name)
            val normalized = DiagnosticInput(input.actionId, JsonObject(input.values + mapOf(
                "model" to JsonPrimitive(model), "protocol" to JsonPrimitive(protocol.value),
            )))
            pluginRuntime.execute(id, expectedActiveVersion, normalized, target) { plan ->
                val channelId = providerChannelProvisioner.ensure(account.provider)
                    ?: throw badRequest("oauth_provider_not_servable", "该提供方的上游协议尚未实现: " + account.provider.value)
                val channel = channelRepository.find(channelId) ?: throw notFound("channel_not_found", "系统端点渠道不存在")
                if (model !in channel.models) throw badRequest("model_not_authorized", "系统端点渠道尚未配置模型「$model」")
                val proxy = try {
                    account.proxyUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { proxyAddress(it, config.oauth.proxyAllowedHosts) }
                } catch (cause: OAuthRefreshException) {
                    throw badRequest("invalid_oauth_proxy", cause.message ?: "账号代理地址无效")
                }
                val token = try {
                    oauthTokenService.accessToken(account.id)
                } catch (cause: CancellationException) {
                    throw cause
                } catch (cause: Throwable) {
                    if (cause is VirtualMachineError) throw cause
                    throw GatewayError(HttpStatusCode.BadGateway, "oauth_credential_unavailable", "读取账号凭据失败", "upstream_error", retryable = true)
                } ?: throw badRequest("oauth_credential_unavailable", "账号还没有可用的 access token")
                val project = if (channel.upstreamEnvelope == UpstreamEnvelope.CODE_ASSIST) {
                    withTimeoutOrNull(20_000) { codeAssistProjectResolver.context(account, token.accessToken, proxy) }?.project
                } else null
                testOAuthAccountModelConnectivity(
                    channel, token.accessToken, project, model, protocol, plan.prompt, upstreamClient(this, proxy),
                    codeAssistUserAgent = currentAntigravityUserAgent(), timeoutMs = plan.timeoutMs,
                    maxOutputTokens = plan.maxOutputTokens,
                    globalOverrides = upstreamRequestSettingsRepository.load().overrides,
                    headerProfiles = upstreamHeaderCatalogService.profiles(),
                ).toPluginOutcome()
            }
        }
        else -> throw badRequest("invalid_plugin_context", "插件目标类型无效")
    }
}

internal suspend fun GatewayApplication.accountPluginTargets(id: String): List<IqTestTargetResponse> {
    val account = oauthAccountRepository.findDetailed(id) ?: throw notFound("oauth_account_not_found", "订阅账号不存在")
    val channelId = providerChannelProvisioner.ensure(account.provider)
        ?: throw badRequest("oauth_provider_not_servable", "该提供方的上游协议尚未实现: " + account.provider.value)
    val channel = channelRepository.find(channelId) ?: throw notFound("channel_not_found", "系统端点渠道不存在")
    val endpointProtocols = ProviderEndpoints.of(account.provider).protocols
    val protocols = channel.protocols.filter { it in endpointProtocols }.map { it.value }
    if (protocols.isEmpty()) return emptyList()
    // 原生账号不需要先作为渠道凭据出现；仅暴露官方端点与账号授权的交集。
    return channel.models.filter { account.supports(it) }.sorted().map { model ->
        IqTestTargetResponse(
            channelId = account.id, channelName = account.name, channelEnabled = channel.enabled,
            model = model, protocols = protocols,
            credentials = listOf(IqTestTargetCredentialResponse(account.id, account.name, account.priority)),
        )
    }
}

internal fun ModelConnectivityTestResult.toPluginOutcome() = ProbeOutcome(
    success, statusCode, latencyMs, responseText, errorMessage,
    usage?.let { GatewayApplication.gatewayJson.encodeToJsonElement(it.toTestResponse()) as JsonObject },
    upstreamReportedModel,
)

internal fun PluginExecution.toLegacyConnectivityResponse() = ChannelModelTestResponse(
    success = outcome.success, model = target.model, protocol = target.protocol,
    keyId = target.credentialId, keyName = target.credentialName,
    statusCode = outcome.statusCode, latencyMs = outcome.latencyMs, prompt = plan.prompt,
    reasoningEffort = JsonNull, responseText = outcome.responseText, errorMessage = outcome.errorMessage,
    usage = outcome.usage?.let { GatewayApplication.gatewayJson.decodeFromJsonElement<ChannelModelTestUsageResponse>(it) },
    upstreamReportedModel = outcome.upstreamReportedModel,
)

internal suspend fun GatewayApplication.connectivitySettings(): ModelTestSettingsResponse {
    val legacy = modelTestSettingsRepository.load()
    val installed = pluginInstallationRepository.find(CONNECTIVITY_PLUGIN_ID)
    val configured = installed?.configuration?.stringValue("defaultPrompt")?.trim()?.takeIf { it.isNotEmpty() }
    return legacy.copy(defaultPrompt = configured ?: legacy.defaultPrompt, updatedAt = installed?.updatedAt ?: legacy.updatedAt).toResponse()
}

internal suspend fun GatewayApplication.saveConnectivitySettings(prompt: String): ModelTestSettingsResponse {
    if (pluginInstallationRepository.find(CONNECTIVITY_PLUGIN_ID) == null) {
        modelTestSettingsRepository.save(prompt)
    } else {
        val state = pluginRuntime.state(CONNECTIVITY_PLUGIN_ID)
        pluginRuntime.configure(CONNECTIVITY_PLUGIN_ID, JsonObject(state.configuration + ("defaultPrompt" to JsonPrimitive(prompt))))
    }
    return connectivitySettings()
}

private fun JsonObject.stringValue(key: String): String? {
    val value = this[key] ?: return null
    if (value == JsonNull) return null
    val primitive = value as? JsonPrimitive
    if (primitive == null || !primitive.isString) throw badRequest("invalid_plugin_input", "字段 $key 必须是文本")
    return primitive.contentOrNull
}
