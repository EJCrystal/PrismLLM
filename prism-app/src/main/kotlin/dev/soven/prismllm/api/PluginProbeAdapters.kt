package dev.soven.prismllm.api

import dev.soven.prismllm.channel.domain.ChannelKeyRecord
import dev.soven.prismllm.channel.domain.ChannelRecord
import dev.soven.prismllm.oauth.domain.OAuthAccountRecord
import dev.soven.prismllm.channel.domain.ChannelKind
import dev.soven.prismllm.channel.domain.ProviderEndpoints
import dev.soven.prismllm.channel.domain.authTypeFor
import dev.soven.prismllm.channel.domain.UpstreamEnvelope
import dev.soven.prismllm.channel.domain.upstreamEnvelope
import dev.soven.prismllm.db.DiagnosticPreparationDeadline
import dev.soven.prismllm.oauth.infra.OAuthRefreshException
import dev.soven.prismllm.oauth.OAuthTokenUnavailableException
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
import dev.soven.prismllm.proxy.resolveSavedCredential
import dev.soven.prismllm.proxy.testChannelModelConnectivity
import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.shared.domain.KeyCredentialResolver
import dev.soven.prismllm.shared.domain.UpstreamCredential
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
import java.net.InetSocketAddress

internal const val CONNECTIVITY_PLUGIN_ID = "prism.connectivity"
internal const val OAUTH_DIAGNOSTIC_PREPARATION_TIMEOUT_MS = 20_000L

private class ResolvedDiagnosticProxy(val address: InetSocketAddress?)

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

private fun sameDiagnosticEndpoint(
    first: ChannelRecord,
    second: ChannelRecord,
    protocol: GatewayProtocol,
): Boolean = first.id == second.id && first.kind == second.kind && first.provider == second.provider &&
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
                suspend fun currentBinding(): Pair<ChannelRecord, UpstreamCredential> {
                    val currentChannel = channelRepository.find(target.id)?.let { channelWithCredentials(this, it) }
                        ?: throw notFound("channel_not_found", "渠道不存在")
                    if (model !in currentChannel.models) throw badRequest("model_not_authorized", "渠道「${currentChannel.name}」尚未授权模型「$model」")
                    if (protocol !in currentChannel.protocols) {
                        throw badRequest("model_test_protocol_unsupported", "渠道未启用协议 ${protocol.value}")
                    }
                    val currentCredential = resolveChannelCredentialForModel(currentChannel, model, target.credentialId)
                    val sameCredential = when (credential) {
                        is ChannelKeyRecord -> currentCredential is ChannelKeyRecord &&
                            currentCredential.credentialCiphertext == credential.credentialCiphertext &&
                            currentCredential.credentialNonce == credential.credentialNonce &&
                            currentCredential.credentialKeyVersion == credential.credentialKeyVersion
                        is OAuthAccountRecord -> currentCredential is OAuthAccountRecord && oauthTokenService.acceptsSnapshot(credential, currentCredential)
                        else -> false
                    }
                    if (!sameCredential || !sameDiagnosticEndpoint(channel, currentChannel, protocol)) {
                        throw badRequest("channel_key_unavailable", "所选渠道凭据或请求配置已变化，请刷新后重试")
                    }
                    return currentChannel to currentCredential
                }
                val (_, selectedCredential) = currentBinding()
                val globalOverrides = upstreamRequestSettingsRepository.load().overrides
                val headerProfiles = upstreamHeaderCatalogService.profiles()
                var preparedAccount: OAuthAccountRecord? = null
                val preparedCredential = resolveSavedCredential(KeyCredentialResolver { candidate ->
                    if (candidate is OAuthAccountRecord) {
                        val bound = oauthTokenService.accessTokenWithSnapshot(candidate.id)
                            ?: throw badRequest("channel_key_unavailable", "所选订阅账号已不可用")
                        if (!oauthTokenService.acceptsSnapshot(candidate, bound.account)) {
                            throw badRequest("channel_key_unavailable", "所选订阅账号凭据已变化，请刷新后重试")
                        }
                        preparedAccount = bound.account
                        bound.token.accessToken
                    } else {
                        oauthTokenService.resolveCredential(candidate)
                    }
                }, selectedCredential)
                val (currentChannel, currentCredential) = currentBinding()
                val preparedSnapshot = preparedAccount
                if (preparedSnapshot != null && (currentCredential !is OAuthAccountRecord || !oauthTokenService.acceptsSnapshot(preparedSnapshot, currentCredential))) {
                    throw badRequest("channel_key_unavailable", "所选订阅账号凭据已变化，请刷新后重试")
                }
                pluginRuntime.requireActivePermission(id, expectedActiveVersion, PluginApi.CHANNEL_PROBE)
                currentCoroutineContext().ensureActive()
                testChannelModelConnectivity(
                    currentChannel, currentCredential, model, protocol, plan.prompt, KeyCredentialResolver { preparedCredential }, httpClient,
                    timeoutMs = plan.timeoutMs, maxOutputTokens = plan.maxOutputTokens,
                    globalOverrides = globalOverrides,
                    headerProfiles = headerProfiles,
                ).toPluginOutcome()
            }
        }
        "oauth-account" -> {
            val preparation = DiagnosticPreparationDeadline.start(oauthDiagnosticPreparationTimeoutMs, oauthDiagnosticNanoTime)
            val initialAccount = DiagnosticPreparationDeadline.withinSynchronous(preparation) {
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
                        DiagnosticPreparationDeadline.withinSynchronous(preparation) {
                            pluginRuntime.requireActivePermission(id, expectedActiveVersion, PluginApi.OAUTH_ACCOUNT_PROBE)
                            Unit
                        } ?: throw oauthPreparationTimeout()
                    }

                    suspend fun revalidateAccount(): dev.soven.prismllm.oauth.domain.OAuthAccountRecord {
                        val latest = DiagnosticPreparationDeadline.withinSynchronous(preparation) {
                            oauthAccountRepository.findDetailed(context.id)
                                ?: throw notFound("oauth_account_not_found", "订阅账号不存在")
                        } ?: throw oauthPreparationTimeout()
                        validateOAuthDiagnosticAccount(latest, context.id, model, protocol)
                        if (!oauthTokenService.acceptsSnapshot(initialAccount, latest)) {
                            throw badRequest("oauth_account_changed", "订阅账号凭据已变化，请刷新后重试")
                        }
                        return latest
                    }

                    suspend fun revalidateSystemChannel(channelId: String): ChannelRecord {
                        val current = DiagnosticPreparationDeadline.withinSynchronous(preparation) {
                            channelRepository.find(channelId) ?: throw notFound("channel_not_found", "系统端点渠道不存在")
                        } ?: throw oauthPreparationTimeout()
                        if (current.kind != ChannelKind.PROVIDER || current.provider != initialAccount.provider || !current.enabled) {
                            throw badRequest("oauth_provider_channel_unavailable", "订阅账号的系统端点渠道不可用")
                        }
                        if (model !in current.models) throw badRequest("model_not_authorized", "系统端点渠道尚未配置模型「$model」")
                        if (protocol !in current.protocols || protocol !in ProviderEndpoints.of(initialAccount.provider).protocols) {
                            throw badRequest("model_test_protocol_unsupported", "系统端点渠道未启用协议 ${protocol.value}")
                        }
                        return current
                    }

                    revalidatePluginGrant()
                    val channelId = DiagnosticPreparationDeadline.withinSynchronous(preparation) {
                        providerChannelProvisioner.ensure(initialAccount.provider)
                            ?: throw badRequest("oauth_provider_not_servable", "该提供方的上游协议尚未实现: " + initialAccount.provider.value)
                    } ?: throw oauthPreparationTimeout()
                    var currentAccount = revalidateAccount()
                    var channel = revalidateSystemChannel(channelId)
                    revalidatePluginGrant()
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
                    val resolvedProxy = try {
                        DiagnosticPreparationDeadline.withinSynchronous(preparation) {
                            ResolvedDiagnosticProxy(proxyAddress(currentAccount.proxyUrl, config.oauth.proxyAllowedHosts))
                        }
                    } catch (cause: OAuthRefreshException) {
                        throw badRequest("invalid_oauth_proxy", cause.message ?: "账号代理地址无效")
                    }
                    val proxy = (resolvedProxy ?: throw oauthPreparationTimeout()).address
                    channel = revalidateSystemChannel(channelId)
                    revalidatePluginGrant()
                    val project = if (channel.upstreamEnvelope == UpstreamEnvelope.CODE_ASSIST) {
                        try {
                            codeAssistProjectResolver.contextForDiagnostic(currentAccount, token.accessToken, proxy, preparation).project
                        } catch (cause: OAuthTokenUnavailableException) {
                            if (cause.errorCode == "credential_replaced") {
                                throw badRequest("oauth_account_changed", "订阅账号凭据已变化，请刷新后重试")
                            }
                            throw cause
                        }
                    } else null

                    // Discovery can be slow. Revalidate the exact token snapshot and endpoint request
                    // configuration after it, so refreshed credentials are never mixed with newer settings.
                    currentAccount = revalidateAccount()
                    if (!token.matches(currentAccount)) throw badRequest("oauth_account_changed", "订阅账号凭据已变化，请刷新后重试")
                    val currentChannel = revalidateSystemChannel(channelId)
                    if (!sameDiagnosticEndpoint(channel, currentChannel, protocol)) {
                        throw badRequest("oauth_provider_channel_changed", "系统端点配置已变化，请刷新后重试")
                    }
                    revalidatePluginGrant()
                    val overrides = DiagnosticPreparationDeadline.withinSynchronous(preparation) {
                        upstreamRequestSettingsRepository.load().overrides
                    } ?: throw oauthPreparationTimeout()
                    val headerProfiles = DiagnosticPreparationDeadline.withinSynchronous(preparation) {
                        upstreamHeaderCatalogService.profiles()
                    } ?: throw oauthPreparationTimeout()

                    currentAccount = revalidateAccount()
                    if (!token.matches(currentAccount)) throw badRequest("oauth_account_changed", "订阅账号凭据已变化，请刷新后重试")
                    channel = revalidateSystemChannel(channelId)
                    if (!sameDiagnosticEndpoint(currentChannel, channel, protocol)) {
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
    val endpoint = ProviderEndpoints.of(account.provider)
    if (!endpoint.servable) {
        throw badRequest("oauth_provider_not_servable", "该提供方的上游协议尚未实现: " + account.provider.value)
    }
    // Resource reads must not persist the hidden provider channel. When it has not been
    // provisioned yet, expose only the provider's seed models/protocols; explicit execution
    // creates the channel under its shared diagnostic-preparation deadline.
    val channel = channelRepository.findProviderChannel(account.provider)
    val models = channel?.models ?: endpoint.models
    val supportedProtocols = channel?.protocols ?: endpoint.protocols
    val protocols = supportedProtocols.filter { it in endpoint.protocols }.map { it.value }
    if (protocols.isEmpty()) return emptyList()
    // 原生账号不需要先作为渠道凭据出现；仅暴露官方端点与账号授权的交集。
    return models.filter { account.supports(it) }.sorted().map { model ->
        IqTestTargetResponse(
            channelId = account.id, channelName = account.name, channelEnabled = channel?.enabled ?: true,
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
    val savedLegacy = connectivitySettingsLock.withLock {
        if (pluginInstallationRepository.findInTransaction(CONNECTIVITY_PLUGIN_ID) == null) {
            modelTestSettingsRepository.saveInTransaction(prompt)
            true
        } else false
    }
    // Never enter the runtime while holding the database lock: installation takes them in the
    // opposite direction. A newly committed install already contains the locked legacy value.
    if (!savedLegacy) {
        pluginRuntime.configureFields(CONNECTIVITY_PLUGIN_ID, JsonObject(mapOf("defaultPrompt" to JsonPrimitive(prompt))))
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
