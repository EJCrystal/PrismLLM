package dev.soven.prismllm.api

import dev.soven.prismllm.channel.domain.ChannelKeyRecord
import dev.soven.prismllm.channel.domain.ChannelRecord
import dev.soven.prismllm.channel.domain.ChannelKind
import dev.soven.prismllm.channel.domain.ProviderEndpoints
import dev.soven.prismllm.channel.domain.authTypeFor
import dev.soven.prismllm.channel.domain.UpstreamEnvelope
import dev.soven.prismllm.channel.domain.upstreamEnvelope
import dev.soven.prismllm.db.DiagnosticPreparationDeadline
import dev.soven.prismllm.oauth.infra.OAuthRefreshException
import dev.soven.prismllm.oauth.infra.proxyAddress
import dev.soven.prismllm.plugin.host.PluginExecution
import dev.soven.prismllm.plugin.host.PluginHostException
import dev.soven.prismllm.plugin.host.PluginUiContribution
import dev.soven.prismllm.plugin.sdk.DiagnosticInput
import dev.soven.prismllm.plugin.sdk.DiagnosticTarget
import dev.soven.prismllm.plugin.sdk.PluginApi
import dev.soven.prismllm.plugin.sdk.ProbeOutcome
import dev.soven.prismllm.proxy.ModelConnectivityTestResult
import dev.soven.prismllm.proxy.prepareOAuthAccountModelConnectivity
import dev.soven.prismllm.proxy.testChannelModelConnectivity
import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.shared.error.GatewayError
import dev.soven.prismllm.shared.error.badRequest
import dev.soven.prismllm.shared.error.notFound
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

internal const val CONNECTIVITY_PLUGIN_ID = "prism.connectivity"
internal const val OAUTH_DIAGNOSTIC_PREPARATION_TIMEOUT_MS = 20_000L

private fun oauthPreparationTimeout() = GatewayError(
    HttpStatusCode.GatewayTimeout,
    "oauth_preparation_timeout",
    "账号准备超时",
    "upstream_error",
    retryable = true,
)

private suspend fun GatewayApplication.validateOAuthDiagnosticAccount(
    account: dev.soven.prismllm.oauth.domain.OAuthAccountRecord,
    expectedId: String,
    model: String,
    protocol: GatewayProtocol,
) {
    if (account.id != expectedId || account.authState != dev.soven.prismllm.oauth.domain.OAuthAuthState.ACTIVE) {
        throw GatewayError(HttpStatusCode.BadGateway, "oauth_credential_unavailable", "读取账号凭据失败", "upstream_error", retryable = true)
    }
    if (!account.supports(model)) throw badRequest("model_not_authorized", "账号「${account.name}」未授权模型「$model」")
    if (protocol !in ProviderEndpoints.of(account.provider).protocols) {
        throw badRequest("model_test_protocol_unsupported", "订阅账号不支持协议 ${protocol.value}")
    }
}

private fun sameOAuthDiagnosticEndpoint(
    first: ChannelRecord,
    second: ChannelRecord,
    protocol: GatewayProtocol,
): Boolean = first.id == second.id && first.provider == second.provider &&
    first.baseUrl == second.baseUrl && first.authTypeFor(protocol) == second.authTypeFor(protocol) &&
    first.protocolAuthTypes == second.protocolAuthTypes && first.requestOverrides == second.requestOverrides &&
    first.upstreamEnvelope == second.upstreamEnvelope

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
    val prompt = request.prompt?.trim()?.takeIf(String::isNotEmpty)
    if (prompt?.length?.let { it > 4_000 } == true) {
        throw badRequest("invalid_model_test_prompt", "测试提示词不能超过 4000 个字符")
    }
    val values = buildJsonObject {
        put("model", request.model)
        put("protocol", request.protocol)
        request.keyId?.takeIf { context.kind == "channel" }?.let { put("keyId", it) }
        prompt?.let { put("prompt", it) }
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
                val currentChannel = channelRepository.find(target.id)?.let { channelWithCredentials(this, it) }
                    ?: throw notFound("channel_not_found", "渠道不存在")
                if (model !in currentChannel.models) throw badRequest("model_not_authorized", "渠道「${currentChannel.name}」尚未授权模型「$model」")
                if (protocol !in currentChannel.protocols) {
                    throw badRequest("model_test_protocol_unsupported", "渠道未启用协议 ${protocol.value}")
                }
                val currentCredential = resolveChannelCredentialForModel(currentChannel, model, target.credentialId)
                if (currentCredential.isOAuth != credential.isOAuth) {
                    throw badRequest("channel_key_unavailable", "所选渠道凭据类型已变化，请刷新后重试")
                }
                testChannelModelConnectivity(
                    currentChannel, currentCredential, model, protocol, plan.prompt, oauthTokenService, httpClient,
                    timeoutMs = plan.timeoutMs, maxOutputTokens = plan.maxOutputTokens,
                    globalOverrides = upstreamRequestSettingsRepository.load().overrides,
                    headerProfiles = upstreamHeaderCatalogService.profiles(),
                ).toPluginOutcome()
            }
        }
        "oauth-account" -> {
            val preparation = DiagnosticPreparationDeadline.start(oauthDiagnosticPreparationTimeoutMs, oauthDiagnosticNanoTime)
            val initialAccount = DiagnosticPreparationDeadline.within(preparation) {
                oauthAccountRepository.findDetailed(context.id)
                    ?: throw notFound("oauth_account_not_found", "订阅账号不存在")
            } ?: throw oauthPreparationTimeout()
            validateOAuthDiagnosticAccount(initialAccount, context.id, model, protocol)
            preparation.pause()
            val target = DiagnosticTarget(
                "oauth-account", context.id, model, protocol.value,
                initialAccount.name, initialAccount.id, initialAccount.name,
            )
            val normalized = DiagnosticInput(input.actionId, JsonObject(input.values + mapOf(
                "model" to JsonPrimitive(model), "protocol" to JsonPrimitive(protocol.value),
            )))
            pluginRuntime.execute(id, expectedActiveVersion, normalized, target) { plan ->
                // Planning and probe-slot waiting happen while the same preparation budget is paused.
                preparation.resume()
                val probe = DiagnosticPreparationDeadline.within(preparation) {
                    suspend fun revalidatePluginGrant() {
                        val state = pluginRuntime.state(id)
                        if (!state.admissionEnabled || state.activeVersion != expectedActiveVersion ||
                            PluginApi.OAUTH_ACCOUNT_PROBE !in state.approvedPermissions
                        ) {
                            throw PluginHostException("PLUGIN_PERMISSION_REQUIRED", "Plugin OAuth diagnostic permission was revoked", 403)
                        }
                    }

                    suspend fun revalidateAccount(): dev.soven.prismllm.oauth.domain.OAuthAccountRecord {
                        val latest = oauthAccountRepository.findDetailed(context.id)
                            ?: throw notFound("oauth_account_not_found", "订阅账号不存在")
                        validateOAuthDiagnosticAccount(latest, context.id, model, protocol)
                        if (latest.provider != initialAccount.provider || latest.authorizedAt != initialAccount.authorizedAt) {
                            throw badRequest("oauth_account_changed", "订阅账号凭据已变化，请刷新后重试")
                        }
                        return latest
                    }

                    suspend fun revalidateSystemChannel(channelId: String): ChannelRecord {
                        val current = channelRepository.find(channelId) ?: throw notFound("channel_not_found", "系统端点渠道不存在")
                        if (current.kind != ChannelKind.PROVIDER || current.provider != initialAccount.provider) {
                            throw badRequest("oauth_provider_channel_unavailable", "订阅账号的系统端点渠道不可用")
                        }
                        if (model !in current.models) throw badRequest("model_not_authorized", "系统端点渠道尚未配置模型「$model」")
                        if (protocol !in current.protocols || protocol !in ProviderEndpoints.of(initialAccount.provider).protocols) {
                            throw badRequest("model_test_protocol_unsupported", "系统端点渠道未启用协议 ${protocol.value}")
                        }
                        return current
                    }

                    revalidatePluginGrant()
                    val channelId = providerChannelProvisioner.ensure(initialAccount.provider)
                        ?: throw badRequest("oauth_provider_not_servable", "该提供方的上游协议尚未实现: " + initialAccount.provider.value)
                    var currentAccount = revalidateAccount()
                    var channel = revalidateSystemChannel(channelId)
                    val token = try {
                        oauthTokenService.accessTokenForDiagnostic(currentAccount, preparation)
                    } catch (cause: CancellationException) {
                        throw cause
                    } catch (cause: Exception) {
                        if (DiagnosticPreparationDeadline.isTimeout(cause)) throw cause
                        throw GatewayError(HttpStatusCode.BadGateway, "oauth_credential_unavailable", "读取账号凭据失败", "upstream_error", retryable = true)
                    }
                    currentAccount = revalidateAccount()
                    if (!token.matches(currentAccount)) throw badRequest("oauth_account_changed", "订阅账号凭据已变化，请刷新后重试")
                    val proxy = try {
                        proxyAddress(currentAccount.proxyUrl, config.oauth.proxyAllowedHosts)
                    } catch (cause: OAuthRefreshException) {
                        throw badRequest("invalid_oauth_proxy", cause.message ?: "账号代理地址无效")
                    }
                    channel = revalidateSystemChannel(channelId)
                    val project = if (channel.upstreamEnvelope == UpstreamEnvelope.CODE_ASSIST) {
                        codeAssistProjectResolver.context(currentAccount, token.accessToken, proxy).project
                    } else null

                    // Discovery can be slow. Revalidate the exact token snapshot and endpoint request
                    // configuration after it, so refreshed credentials are never mixed with newer settings.
                    currentAccount = revalidateAccount()
                    if (!token.matches(currentAccount)) throw badRequest("oauth_account_changed", "订阅账号凭据已变化，请刷新后重试")
                    val currentChannel = revalidateSystemChannel(channelId)
                    if (!sameOAuthDiagnosticEndpoint(channel, currentChannel, protocol)) {
                        throw badRequest("oauth_provider_channel_changed", "系统端点配置已变化，请刷新后重试")
                    }
                    revalidatePluginGrant()
                    val overrides = upstreamRequestSettingsRepository.load().overrides
                    val headerProfiles = upstreamHeaderCatalogService.profiles()

                    currentAccount = revalidateAccount()
                    if (!token.matches(currentAccount)) throw badRequest("oauth_account_changed", "订阅账号凭据已变化，请刷新后重试")
                    channel = revalidateSystemChannel(channelId)
                    if (!sameOAuthDiagnosticEndpoint(currentChannel, channel, protocol)) {
                        throw badRequest("oauth_provider_channel_changed", "系统端点配置已变化，请刷新后重试")
                    }
                    revalidatePluginGrant()
                    prepareOAuthAccountModelConnectivity(
                        channel, token.accessToken, project, model, protocol, plan.prompt, upstreamClient(this, proxy),
                        codeAssistUserAgent = currentAntigravityUserAgent(headerProfiles), timeoutMs = plan.timeoutMs,
                        maxOutputTokens = plan.maxOutputTokens,
                        globalOverrides = overrides,
                        headerProfiles = headerProfiles,
                    )
                } ?: throw oauthPreparationTimeout()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                probe().toPluginOutcome()
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
