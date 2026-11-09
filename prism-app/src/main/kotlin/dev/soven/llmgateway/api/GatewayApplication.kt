package dev.soven.llmgateway.api

import dev.soven.llmgateway.shared.error.AnthropicErrorBody
import dev.soven.llmgateway.shared.error.AnthropicErrorEnvelope
import dev.soven.llmgateway.shared.error.ErrorBody
import dev.soven.llmgateway.shared.error.ErrorEnvelope
import dev.soven.llmgateway.shared.error.GatewayError
import dev.soven.llmgateway.shared.error.GeminiErrorEnvelope
import dev.soven.llmgateway.shared.error.anthropicErrorType
import dev.soven.llmgateway.shared.error.toAnthropicEnvelope
import dev.soven.llmgateway.shared.error.toEnvelope
import dev.soven.llmgateway.shared.error.toGeminiEnvelope

import dev.soven.llmgateway.config.GatewayConfig
import dev.soven.llmgateway.config.TrustedProxyNetwork
import dev.soven.llmgateway.db.DatabaseFactory
import dev.soven.llmgateway.proxy.BrowserScreenshotRenderer
import dev.soven.llmgateway.proxy.GatewayMetrics
import dev.soven.llmgateway.proxy.HtmlImageRenderer
import dev.soven.llmgateway.proxy.UnavailableBrowserRenderer
import dev.soven.llmgateway.proxy.isGeminiRequestPath
import dev.soven.llmgateway.requestlog.infra.RequestLogWriter
import dev.soven.llmgateway.proxy.UpstreamOccupancy
import dev.soven.llmgateway.repo.ModelPriceRepository
import dev.soven.llmgateway.repo.ModelDefaultPriceRepository
import dev.soven.llmgateway.pricing.domain.ModelPricingService
import dev.soven.llmgateway.routing.readmodel.OAuthAccountDirectory
import dev.soven.llmgateway.routing.readmodel.UpstreamModelCatalog
import dev.soven.llmgateway.proxy.ModelPriceReferenceService
import dev.soven.llmgateway.proxy.CurrencyExchangeService
import dev.soven.llmgateway.proxy.UpstreamHeaderCatalogService
import dev.soven.llmgateway.repo.AdminUserRepository
import dev.soven.llmgateway.repo.AdminAuditRepository
import dev.soven.llmgateway.channel.persistence.ChannelRepository
import dev.soven.llmgateway.repo.ClientKeyRepository
import dev.soven.llmgateway.repo.ClientKeyQuotaRepository
import dev.soven.llmgateway.repo.CooldownStateRepository
import dev.soven.llmgateway.repo.RequestLogRepository
import dev.soven.llmgateway.repo.RetentionSettingsRepository
import dev.soven.llmgateway.oauth.OAuthAccountRouting
import dev.soven.llmgateway.oauth.infra.OAuthAccountScheduler
import dev.soven.llmgateway.oauth.OAuthLoginService
import dev.soven.llmgateway.oauth.OAuthQuotaService
import dev.soven.llmgateway.oauth.infra.OAuthTokenProviderRegistry
import dev.soven.llmgateway.oauth.OAuthTokenService
import dev.soven.llmgateway.oauth.ProviderChannelProvisioner
import dev.soven.llmgateway.proxy.codeassist.CodeAssistProjectResolver
import dev.soven.llmgateway.proxy.codeassist.CodeAssistModelCatalog
import dev.soven.llmgateway.proxy.codeassist.CodeAssistQuotaClient
import dev.soven.llmgateway.oauth.infra.OAuthWindowStateWriter
import dev.soven.llmgateway.oauth.buildOAuthTokenProviders
import dev.soven.llmgateway.repo.ModelTestSettingsRepository
import dev.soven.llmgateway.repo.WebSearchSettingsRepository
import dev.soven.llmgateway.repo.OAuthAccountRepository
import dev.soven.llmgateway.repo.OAuthWindowRepository
import dev.soven.llmgateway.repo.UpstreamRequestSettingsRepository
import dev.soven.llmgateway.repo.UpstreamHeaderCatalogRepository
import dev.soven.llmgateway.channel.persistence.KeyAutoDisableRepository
import dev.soven.llmgateway.channel.persistence.KeyStateAudit
import dev.soven.llmgateway.channel.persistence.ChannelKeyStateChangedException
import dev.soven.llmgateway.requestlog.infra.LogRetentionService
import dev.soven.llmgateway.quota.infra.ClientKeyQuotaService
import dev.soven.llmgateway.channel.domain.key.ChannelKeyRateLimiter
import dev.soven.llmgateway.quota.infra.RequestLimitService
import dev.soven.llmgateway.security.AdminSessions
import dev.soven.llmgateway.protocol.ProtocolConversion
import dev.soven.llmgateway.shared.crypto.CredentialCipher
import dev.soven.llmgateway.shared.crypto.PasswordHasher
import dev.soven.llmgateway.channel.infra.quota.ChannelQuotaProviderRegistry
import dev.soven.llmgateway.channel.infra.quota.ChannelQuotaService
import dev.soven.llmgateway.resilience.CandidateCooldown
import dev.soven.llmgateway.resilience.CooldownStateWriter
import dev.soven.llmgateway.resilience.KeyAvailabilityGate
import dev.soven.llmgateway.resilience.TierSharing
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respondText
import io.ktor.server.response.header
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val REQUEST_ID_HEADER = "X-Request-Id"
private const val CLOUDFLARE_CONNECTING_IP_HEADER = "CF-Connecting-IP"
private const val FORWARDED_FOR_HEADER = "X-Forwarded-For"
private const val REAL_IP_HEADER = "X-Real-IP"
private const val FORWARDED_HEADER = "Forwarded"
private const val MAX_USER_AGENT_LOG_LENGTH = 512

internal data class RequestAccessLogMetadata(
    val requestId: String,
    val clientIp: String,
    val clientIpSource: String,
    val peerIp: String,
    val userAgent: String,
)

internal fun requestAccessLogMetadata(
    requestId: String?,
    cloudflareConnectingIp: String?,
    forwardedFor: String?,
    realIp: String?,
    forwarded: String?,
    peerIp: String?,
    userAgent: String?,
    trustedProxyNetworks: List<TrustedProxyNetwork> = emptyList(),
): RequestAccessLogMetadata {
    val peer = sanitizeLogValue(peerIp)
    val candidates = if (trustedProxyNetworks.any { it.contains(peerIp) }) {
        listOf(
            "cf-connecting-ip" to firstHeaderValue(cloudflareConnectingIp),
            "x-forwarded-for" to forwardedForClientIp(forwardedFor, trustedProxyNetworks),
            "x-real-ip" to firstHeaderValue(realIp),
            "forwarded" to forwardedIp(forwarded, trustedProxyNetworks),
            "peer" to peer.takeUnless { it == "-" },
        )
    } else {
        listOf("peer" to peer.takeUnless { it == "-" })
    }
    val (source, address) = candidates.firstOrNull { !it.second.isNullOrBlank() }
        ?: ("unknown" to "-")
    return RequestAccessLogMetadata(
        requestId = sanitizeLogValue(requestId),
        clientIp = address ?: "-",
        clientIpSource = source,
        peerIp = peer,
        userAgent = sanitizeLogValue(userAgent, MAX_USER_AGENT_LOG_LENGTH),
    )
}

internal fun requestAccessLogMetadata(
    call: io.ktor.server.application.ApplicationCall,
    trustedProxyNetworks: List<TrustedProxyNetwork>,
): RequestAccessLogMetadata =
    requestAccessLogMetadata(
        requestId = call.response.headers[REQUEST_ID_HEADER] ?: call.request.headers[REQUEST_ID_HEADER],
        cloudflareConnectingIp = call.request.headers[CLOUDFLARE_CONNECTING_IP_HEADER],
        forwardedFor = joinedHeaderValues(call.request.headers, FORWARDED_FOR_HEADER),
        realIp = call.request.headers[REAL_IP_HEADER],
        forwarded = joinedHeaderValues(call.request.headers, FORWARDED_HEADER),
        peerIp = call.request.local.remoteAddress,
        userAgent = call.request.headers[HttpHeaders.UserAgent],
        trustedProxyNetworks = trustedProxyNetworks,
    )

internal fun joinedHeaderValues(headers: io.ktor.http.Headers, name: String): String? =
    headers.getAll(name)?.joinToString(",")

private fun firstHeaderValue(value: String?): String? =
    value
        ?.split(',')
        ?.asSequence()
        ?.mapNotNull(::parseHeaderIp)
        ?.firstOrNull()

private fun forwardedForClientIp(
    value: String?,
    trustedProxyNetworks: List<TrustedProxyNetwork>,
): String? =
    value
        ?.split(',')
        ?.asReversed()
        ?.asSequence()
        ?.mapNotNull(::parseHeaderIp)
        ?.firstOrNull { address -> trustedProxyNetworks.none { it.contains(address) } }

private fun forwardedIp(value: String?, trustedProxyNetworks: List<TrustedProxyNetwork>): String? =
    value
        ?.split(',')
        ?.asReversed()
        ?.asSequence()
        ?.mapNotNull { element ->
            element.split(';')
                .asSequence()
                .map(String::trim)
                .filter { it.startsWith("for=", ignoreCase = true) }
                .mapNotNull { it.substringAfter('=', "").let(::parseHeaderIp) }
                .firstOrNull()
        }
        ?.firstOrNull { address -> trustedProxyNetworks.none { it.contains(address) } }

private fun parseHeaderIp(value: String): String? {
    val candidate = value.trim().trim('"')
    val address = when {
        candidate.startsWith('[') -> {
            val end = candidate.indexOf(']')
            if (end <= 1) return null
            val portSuffix = candidate.substring(end + 1)
            if (portSuffix.isNotEmpty() && (!portSuffix.startsWith(':') || !isValidPort(portSuffix.substring(1)))) {
                return null
            }
            candidate.substring(1, end)
        }
        candidate.count { it == ':' } == 1 && candidate.contains('.') -> {
            val host = candidate.substringBeforeLast(':')
            if (!isValidPort(candidate.substringAfterLast(':'))) return null
            host
        }
        else -> candidate
    }
    val normalizedAddress = address.trim()
    return normalizedAddress.takeIf(TrustedProxyNetwork::isIpAddress)
}

private fun isValidPort(value: String): Boolean =
    value.isNotEmpty() && value.all { it in '0'..'9' } && value.toIntOrNull() in 0..65535

private fun sanitizeLogValue(value: String?, maxLength: Int = 128): String {
    val normalized = value
        ?.replace(Regex("[\\r\\n\\t]+"), " ")
        ?.trim()
        ?.takeUnless { it.isNullOrBlank() }
        ?: return "-"
    return escapeLogValue(normalized, maxLength)
}

private fun escapeLogValue(value: String, maxLength: Int): String {
    val escaped = StringBuilder(minOf(value.length, maxLength))
    for (character in value) {
        val replacement = when (character) {
            '%' -> "%25"
            ' ' -> "%20"
            '=' -> "%3D"
            else -> character.toString()
        }
        if (escaped.length + replacement.length > maxLength) break
        escaped.append(replacement)
    }
    return escaped.toString().ifEmpty { "-" }
}

/**
 * 网关运行时装配。负责数据库迁移、管理员初始化、插件安装和资源生命周期。
 *
 * @author 文杰
 * @since 2026-10-21
 */
class GatewayApplication(
    val config: GatewayConfig,
    /** 测试可注入渲染器；生产环境留空，自动使用无头浏览器。 */
    private val visionRendererOverride: HtmlImageRenderer? = null,
) : AutoCloseable {
    init {
        // 协议组合缺失只会在对应请求到达时才暴露，放到启动阶段拦截。
        ProtocolConversion.selfCheck()
    }

    private val logger = LoggerFactory.getLogger(GatewayApplication::class.java)
    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val shuttingDown = AtomicBoolean(false)
    val databaseFactory = DatabaseFactory(config)
    val credentialCipher = CredentialCipher(config.masterKey)
    val channelRepository = ChannelRepository(databaseFactory.database, credentialCipher)
    val clientKeyRepository = ClientKeyRepository(databaseFactory.database)
    val clientKeyQuotaRepository = ClientKeyQuotaRepository(databaseFactory.database)
    val clientKeyQuotaService = ClientKeyQuotaService(
        usageReader = clientKeyQuotaRepository::usage,
        usageWriter = clientKeyQuotaRepository::addUsage,
    )
    val requestLimitService = RequestLimitService(clientKeyQuotaService)
    val adminUserRepository = AdminUserRepository(databaseFactory.database)
    val adminAuditRepository = AdminAuditRepository(databaseFactory.database)
    val keyStateAudit = KeyStateAudit { actor, action, method, path, statusCode, requestId, sourceIp, errorCode, details ->
        adminAuditRepository.recordInTransaction(
            actor = actor,
            action = action,
            method = method,
            path = path,
            statusCode = statusCode,
            requestId = requestId,
            sourceIp = sourceIp,
            errorCode = errorCode,
            details = details,
        )
    }
    val keyAutoDisableRepository = KeyAutoDisableRepository(databaseFactory.database, keyStateAudit)
    val keyAvailabilityGate = KeyAvailabilityGate()
    val channelKeyRateLimiter = ChannelKeyRateLimiter()
    val upstreamOccupancy = UpstreamOccupancy()
    val requestLogRepository = RequestLogRepository(databaseFactory.database)
    val channelHealthRepository = dev.soven.llmgateway.repo.ChannelHealthRepository(databaseFactory.database, config.cooldown.ttftSlowThresholdMs)
    private var channelHealthIndexJob: Job? = null
    val retentionSettingsRepository = RetentionSettingsRepository(databaseFactory.database)
    val modelTestSettingsRepository = ModelTestSettingsRepository(databaseFactory.database)
    val webSearchSettingsRepository = WebSearchSettingsRepository(databaseFactory.database, credentialCipher)
    val iqTestRunRepository = dev.soven.llmgateway.repo.IqTestRunRepository(databaseFactory.database)
    val upstreamRequestSettingsRepository = UpstreamRequestSettingsRepository(databaseFactory.database)
    val upstreamHeaderCatalogRepository = UpstreamHeaderCatalogRepository(databaseFactory.database)
    val modelPriceRepository = ModelPriceRepository(databaseFactory.database)
    val modelDefaultPriceRepository = ModelDefaultPriceRepository(databaseFactory.database)
    val modelPricingService = ModelPricingService(modelPriceRepository, modelDefaultPriceRepository)
    val sessions = AdminSessions()
    val cooldownStateRepository = CooldownStateRepository(databaseFactory.database)
    val cooldownStateWriter = CooldownStateWriter(cooldownStateRepository, scope)
    val candidateCooldown = CandidateCooldown(
        policy = config.cooldown,
        onChange = cooldownStateWriter::submit,
    )
    val tierSharing = TierSharing()
    val metrics = GatewayMetrics()
val requestLogWriter = RequestLogWriter(
        insertRecord = requestLogRepository::insert,
        resolvePrice = { record ->
            modelPricingService.resolve(record.channelId, record.upstreamModel, record.pricingModelId)
        },
        scope = scope,
    )
    val logRetentionService = LogRetentionService(requestLogRepository::deleteOlderThan, config.retention)
    private val logRetentionJob = java.util.concurrent.atomic.AtomicReference<Job?>(null)
    private val logRetentionMutex = Mutex()
    private val keyAutoRecoveryJob = AtomicReference<Job?>(null)

    val httpClient = HttpClient(Java) {
        install(HttpTimeout) {
            connectTimeoutMillis = config.connectTimeoutMs
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        }
    }

    /**
     * 按账号出口创建的数据面客户端。账号配置了 proxy_url 时，对话请求与刷新 token
     * 必须走同一个代理，因此按代理地址复用一条客户端（含连接池），关闭网关时统一关闭。
     */
    val accountEgressClients = java.util.concurrent.ConcurrentHashMap<String, HttpClient>()

    /** 搜索后端不跟随跳转，避免把 API Key 带到别的站点。 */
    val searchHttpClient = HttpClient(Java) {
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        }
    }

    val manxueHtmlClassifier = dev.soven.llmgateway.proxy.ManxueHtmlClassifier(
        httpClient = httpClient,
        baseUrl = config.manxueApiBaseUrl,
        timeoutMs = config.manxueTimeoutMs,
    )
    /** 部署默认值；管理台未保存配置时使用，也用于判断密钥是否来自环境变量。 */
    val iqVisionDefaults = dev.soven.llmgateway.domain.IqVisionConfig(
        enabled = config.iqVisionEnabled,
        baseUrl = config.iqVisionBaseUrl,
        model = config.iqVisionModel,
        apiKey = config.iqVisionApiKey.takeIf { it.isNotBlank() },
        timeoutMs = config.iqVisionTimeoutMs,
    )
    val iqVisionSettingsRepository = dev.soven.llmgateway.repo.IqVisionSettingsRepository(
        database = databaseFactory.database,
        credentialCipher = credentialCipher,
    )
    /** 浏览器在启动期解析；管理台据此提示「视觉判分不可用」。 */
    private val visionRenderer: HtmlImageRenderer = visionRendererOverride ?: createVisionRenderer()
    val iqVisionBrowserAvailable: Boolean
        get() = visionRenderer.available
    /**
     * 视觉判分器始终注册：是否真正调用模型由「系统配置」里的开关决定，
     * 这样管理员不必为改一个模型名重启网关。浏览器仍在启动期解析，
     * 更换浏览器路径需要重启。
     */
    val iqJudgeRegistry = dev.soven.llmgateway.plugin.iq.IqJudgeRegistry(
        pluginDirectory = config.iqJudgePluginDir,
        builtIns = buildList {
            add(dev.soven.llmgateway.plugin.iq.LocalStructureIqJudge())
            if (config.manxueEnabled) {
                add(dev.soven.llmgateway.plugin.iq.ManxueIqJudge(manxueHtmlClassifier))
            }
            add(
                dev.soven.llmgateway.plugin.iq.VisionIqJudge(
                    renderer = visionRenderer,
                    // 每次判分重新读取，管理台保存后立即生效；读库失败时返回不可用而不是回退到环境变量。
                    settings = { iqVisionSettingsRepository.loadBlocking(iqVisionDefaults) },
                    assessorFactory = { resolved ->
                        dev.soven.llmgateway.proxy.OpenAiVisionAssessor(
                            httpClient = httpClient,
                            baseUrl = resolved.baseUrl,
                            apiKey = resolved.apiKey,
                            model = resolved.model,
                            timeoutMs = resolved.timeoutMs,
                        )
                    },
                ),
            )
        },
    )
    val iqJudgeService = dev.soven.llmgateway.plugin.iq.IqJudgeService(
        registry = iqJudgeRegistry,
        timeoutMs = config.iqJudgeTimeoutMs,
    )

    private fun createVisionRenderer(): HtmlImageRenderer {
        val visionBudgetMs = 6 * config.iqVisionBrowserTimeoutMs + config.iqVisionTimeoutMs
        if (config.iqJudgeTimeoutMs < visionBudgetMs) {
            logger.warn(
                "IQ_JUDGE_TIMEOUT_MS={}ms 小于视觉判分最坏预算 {}ms（6 帧渲染 + 视觉模型），" +
                    "判分可能提前超时并转为需人工查看",
                config.iqJudgeTimeoutMs,
                visionBudgetMs,
            )
        }
        val executable = BrowserScreenshotRenderer.resolveExecutable(config.iqVisionBrowserPath)
        if (executable == null) {
            if (config.iqVisionBrowserPath.isNotBlank()) {
                logger.warn("IQ_VISION_BROWSER_PATH 指向的浏览器不可执行：{}", config.iqVisionBrowserPath)
            }
            logger.warn(
                "未找到无头浏览器，IQ 视觉判分将返回需人工查看；" +
                    "请安装 Chrome/Chromium 或设置 IQ_VISION_BROWSER_PATH。",
            )
            return UnavailableBrowserRenderer
        }
        logger.info("IQ 视觉判分使用无头浏览器：{}", executable)
        return BrowserScreenshotRenderer(
            executable = executable,
            maxWidth = config.iqVisionRenderWidth,
            maxImageBytes = config.iqVisionMaxImageBytes,
            timeoutMs = config.iqVisionBrowserTimeoutMs,
        )
    }

    val modelPriceReferenceService = ModelPriceReferenceService(httpClient)
    val currencyExchangeService = CurrencyExchangeService(httpClient)
    val upstreamHeaderCatalogService = UpstreamHeaderCatalogService(
        httpClient = httpClient,
        repository = upstreamHeaderCatalogRepository,
        refreshIntervalMs = config.upstreamHeaderRefreshIntervalMs,
        autoRefreshEnabled = config.upstreamHeaderRefreshEnabled,
        manifestUrl = config.upstreamHeaderCatalogUrl,
    )
    /** 订阅账号 access token 解析与后台轮换；未配置任何 Provider 时后台任务不扫描。 */
    val oauthAccountRepository = OAuthAccountRepository(databaseFactory.database)
    /** 订阅账号的官方端点渠道：按 provider 置备，账号导入时自动挂上。 */
    val providerChannelProvisioner = ProviderChannelProvisioner(channelRepository, oauthAccountRepository)
    /**
     * Code Assist 的账号出口连接池：project 发现、目录与额度共用，
     * 由应用统一关闭，避免关闭网关后遗留代理客户端。
     */
    private val codeAssistClients = dev.soven.llmgateway.proxy.codeassist.CodeAssistClientPool(httpClient)
    /** Antigravity 的 Code Assist project/tier 发现与缓存。 */
    val codeAssistProjectResolver = CodeAssistProjectResolver(
        oauthAccountRepository,
        httpClient,
        proxyAllowedHosts = config.oauth.proxyAllowedHosts,
        clients = codeAssistClients,
    )
    /** Antigravity 的模型目录与家族/变体映射。 */
    val codeAssistModelCatalog = CodeAssistModelCatalog(httpClient, clients = codeAssistClients)
    /** Antigravity 的额度读取。 */
    val codeAssistQuotaClient = CodeAssistQuotaClient(httpClient, clients = codeAssistClients)
    val modelCatalog = UpstreamModelCatalog(
        channelRepository,
        OAuthAccountDirectory { oauthAccountRepository.listByChannel() },
    )
    val oauthTokenProviders = OAuthTokenProviderRegistry(buildOAuthTokenProviders(config.oauth))
    val oauthTokenService = OAuthTokenService(oauthAccountRepository, credentialCipher, oauthTokenProviders, config.oauth)
    /** 交互式 OAuth 登录：PKCE 会话只保存在内存，单次使用。 */
    val oauthLoginService = OAuthLoginService(
        oauthAccountRepository,
        credentialCipher,
        config.oauth,
    )
    /** 额度插件仅在管理面查询阶段使用，不参与请求转发。 */
    val channelQuotaProviderRegistry = ChannelQuotaProviderRegistry(config.quotaPluginDir)
    val channelQuotaService = ChannelQuotaService(channelQuotaProviderRegistry, credentialCipher, oauthTokenService)
    /** 订阅账号级调度：账号级滑动窗口、单号并发与 429 冷却。内存态权威，快照异步落库。 */
    val oauthWindowRepository = OAuthWindowRepository(databaseFactory.database)
    val oauthWindowStateWriter = OAuthWindowStateWriter(oauthWindowRepository::upsert, scope)
    val oauthAccountScheduler = OAuthAccountScheduler(
        defaultCooldownMs = config.oauth.rateLimitDefaultMs,
        maxCooldownMs = config.oauth.rateLimitMaxMs,
        quotaMaxAgeMs = config.oauth.quotaMaxAgeMs,
        onSnapshot = oauthWindowStateWriter::submit,
    )
    /** 订阅账号在候选池内的排序策略；只重排同一渠道同一优先级内的账号。 */
    val oauthAccountRouting = OAuthAccountRouting(
        strategy = config.oauth.routing,
        hints = oauthAccountScheduler::routingHints,
    )
    /** 上游额度观测：定时读取 ChatGPT 用量，同步给调度器做额度准入。 */
    val oauthQuotaService = OAuthQuotaService(
        repository = oauthAccountRepository,
        tokenService = oauthTokenService,
        config = config.oauth,
        projectResolver = codeAssistProjectResolver,
        antigravityQuota = codeAssistQuotaClient,
        onQuota = oauthAccountScheduler::noteQuota,
    )

    fun install(app: Application) {
        databaseFactory.migrate()
        channelHealthIndexJob = dev.soven.llmgateway.requestlog.infra.startChannelHealthIndexer(
            { cutoff -> requestLogRepository.indexHealthBatch(cutoff) },
            scope,
        )
        runBlocking { upstreamRequestSettingsRepository.load() }
        runBlocking { upstreamHeaderCatalogService.initialize() }
        runBlocking { seedAdmin() }
        // 每次启动都处理 V54 留下的待搬迁账号；逐账号清除标记，上次失败的重启后继续。
        runBlocking { providerChannelProvisioner.reconcile() }
        runBlocking { candidateCooldown.restore(cooldownStateRepository.loadAll()) }
        runBlocking { oauthAccountScheduler.restore(oauthWindowRepository.loadAll()) }
        runBlocking {
            // 上次运行留下的额度读数立即恢复，重启后不必等第一轮扫描才能拦截已用尽账号。
            oauthAccountRepository.listAll().forEach { account ->
                account.quota?.let { oauthAccountScheduler.noteQuota(account.id, it) }
            }
        }
        runBlocking {
            val storedCleanupEnabled = retentionSettingsRepository.loadCleanupEnabled()
            if (storedCleanupEnabled != null) {
                logRetentionService.setCleanupEnabled(storedCleanupEnabled)
                logger.info("request log cleanup setting from database: {}", storedCleanupEnabled)
            } else {
                logger.info(
                    "request log cleanup setting from environment: {}",
                    logRetentionService.currentPolicy().cleanupEnabled,
                )
            }
        }

        app.install(ContentNegotiation) {
            json(gatewayJson)
        }
        app.install(StatusPages) {
            exception<ChannelKeyStateChangedException> { call, cause ->
                call.markAdminAuditError("channel_key_state_changed")
                call.respondGatewayError(
                    HttpStatusCode.Conflict,
                    "channel_key_state_changed",
                    cause.message ?: "密钥状态已变化，请重新打开渠道编辑页面后再保存",
                    "invalid_request_error",
                )
            }
            exception<UnsupportedMediaTypeException> { call, _ ->
                call.markAdminAuditError("unsupported_media_type")
                call.respondGatewayError(
                    HttpStatusCode.UnsupportedMediaType,
                    "unsupported_media_type",
                    "请求体必须是 JSON",
                    "invalid_request_error",
                )
            }
            exception<ContentTransformationException> { call, _ ->
                call.markAdminAuditError("invalid_request")
                call.respondGatewayError(
                    HttpStatusCode.BadRequest,
                    "invalid_request",
                    "请求体格式错误",
                    "invalid_request_error",
                )
            }
            exception<BadRequestException> { call, _ ->
                call.markAdminAuditError("invalid_request")
                call.respondGatewayError(
                    HttpStatusCode.BadRequest,
                    "invalid_request",
                    "请求体格式错误",
                    "invalid_request_error",
                )
            }
            exception<GatewayError> { call, cause ->
                call.markAdminAuditError(cause.code)
                if (call.response.isCommitted) {
                    val accessLog = requestAccessLogMetadata(call, config.trustedProxyNetworks)
                    logger.warn(
                        "gateway error after response committed, code={}, requestId={}, clientIp={}, " +
                            "clientIpSource={}, peerIp={}, userAgent={}",
                        cause.code,
                        accessLog.requestId,
                        accessLog.clientIp,
                        accessLog.clientIpSource,
                        accessLog.peerIp,
                        accessLog.userAgent,
                    )
                    return@exception
                }
                cause.retryAfterMs?.let { retryAfterMs ->
                    val retryAfterSeconds = ((retryAfterMs + 999) / 1_000).coerceAtLeast(1)
                    call.response.header(HttpHeaders.RetryAfter, retryAfterSeconds.toString())
                }
                val nativeResponseBody = cause.nativeResponseBody
                if ((call.request.path() == "/v1/messages" || isGeminiRequestPath(call.request.path())) && nativeResponseBody != null) {
                    call.respondText(
                        nativeResponseBody,
                        ContentType.Application.Json,
                        cause.upstreamStatus?.let(HttpStatusCode::fromValue) ?: cause.status,
                    )
                    return@exception
                }
                if (call.request.path() == "/v1/messages") {
                    call.respondText(
                        gatewayJson.encodeToString(AnthropicErrorEnvelope.serializer(), cause.toAnthropicEnvelope()),
                        ContentType.Application.Json,
                        cause.status,
                    )
                    return@exception
                }
                if (isGeminiRequestPath(call.request.path())) {
                    call.respondText(
                        gatewayJson.encodeToString(GeminiErrorEnvelope.serializer(), cause.toGeminiEnvelope()),
                        ContentType.Application.Json, cause.status,
                    )
                    return@exception
                }
                call.respondText(
                    gatewayJson.encodeToString(ErrorEnvelope.serializer(), cause.toEnvelope()),
                    ContentType.Application.Json,
                    cause.status,
                )
            }
            exception<Throwable> { call, cause ->
                call.markAdminAuditError("internal_error")
                logger.error("unhandled request error", cause)
                if (!call.response.isCommitted) {
                    call.respondGatewayError(
                        HttpStatusCode.InternalServerError,
                        "internal_error",
                        "网关内部错误",
                        "api_error",
                    )
                }
            }
        }
        app.install(adminAuditPlugin(adminAuditRepository, logger, metrics::adminAuditWriteFailed))
        app.install(CORS) {
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Delete)
            allowHeader(HttpHeaders.Authorization)
            allowHeader("x-api-key")
            allowHeader("x-goog-api-key")
            allowHeader("anthropic-version")
            allowHeader("anthropic-beta")
            allowHeader(HttpHeaders.ContentType)
            allowHeader("X-CSRF-Token")
            allowHeader("X-Request-Id")
            allowHeader("X-Prompt-Cache-Key")
            allowHeader("traceparent")
            exposeHeader("traceparent")
            exposeHeader("X-Trace-Id")
            exposeHeader("X-Request-Id")
            config.corsOrigins.forEach { origin ->
                val uri = runCatching { URI(origin) }.getOrNull() ?: return@forEach
                val host = uri.host ?: return@forEach
                val hostWithPort = if (uri.port > 0) "$host:${uri.port}" else host
                allowHost(hostWithPort, schemes = listOf(uri.scheme))
            }
        }
        app.install(CallLogging) {
            level = Level.INFO
            filter { call -> call.request.path().startsWith("/v1/") || call.request.path().startsWith("/v1beta/") }
            disableDefaultColors()
            // Gemini accepts query credentials; keep the access-log contract independent of Ktor defaults.
            format { call ->
                val accessLog = requestAccessLogMetadata(call, config.trustedProxyNetworks)
                "${call.response.status() ?: "Unhandled"}: ${call.request.httpMethod.value} - " +
                    "${call.request.path()} in ${call.processingTimeMillis()}ms " +
                    "requestId=${accessLog.requestId} clientIp=${accessLog.clientIp} " +
                    "clientIpSource=${accessLog.clientIpSource} peerIp=${accessLog.peerIp} " +
                    "userAgent=${accessLog.userAgent}"
            }
        }
        app.install(MicrometerMetrics) {
            registry = metrics.registry
        }

        app.routing {
            healthRoutes(this@GatewayApplication)
            proxyRoutes(this@GatewayApplication)
            adminRoutes(this@GatewayApplication)
            adminUi(this@GatewayApplication)
        }
        app.monitor.subscribe(ApplicationStopped) { close() }
        modelPriceReferenceService.start(scope)
        if (config.upstreamHeaderRefreshEnabled) upstreamHeaderCatalogService.start(scope)
        startKeyAutoRecovery()
        oauthTokenService.start(scope)
        oauthQuotaService.start(scope)
        runBlocking { syncLogRetentionJob() }
    }

    fun beginShutdown() {
        shuttingDown.set(true)
    }

    suspend fun updateLogCleanupEnabled(enabled: Boolean) = logRetentionMutex.withLock {
        retentionSettingsRepository.saveCleanupEnabled(enabled)
        logRetentionService.setCleanupEnabled(enabled)
        if (enabled) startLogRetentionJobLocked() else stopLogRetentionJobLocked()
    }

    private suspend fun syncLogRetentionJob() = logRetentionMutex.withLock {
        if (logRetentionService.currentPolicy().cleanupEnabled) {
            startLogRetentionJobLocked()
        } else {
            stopLogRetentionJobLocked()
        }
    }

    private fun startLogRetentionJobLocked() {
        if (logRetentionJob.get() == null) {
            logRetentionJob.set(logRetentionService.start(scope))
        }
    }

    private suspend fun stopLogRetentionJobLocked() {
        logRetentionJob.getAndSet(null)?.cancelAndJoin()
    }

    private fun startKeyAutoRecovery() {
        if (keyAutoRecoveryJob.get() != null) return
        val intervalMs = config.keyAutoRecoveryIntervalMs
        logger.info("channel key auto recovery started, intervalMs={}", intervalMs)
        keyAutoRecoveryJob.set(scope.launch {
            delay(intervalMs)
            while (isActive) {
                try {
                    val recovered = keyAutoDisableRepository.recoverExpired()
                    recovered.forEach { candidateCooldown.clearKey(it.channelId, it.keyId) }
                    if (recovered.isNotEmpty()) {
                        logger.info("recovered {} expired channel keys", recovered.size)
                    }
                } catch (cause: CancellationException) {
                    throw cause
                } catch (cause: Throwable) {
                    logger.error("channel key auto recovery cleanup failed", cause)
                }
                delay(intervalMs)
            }
        })
    }

    private suspend fun seedAdmin() {
        if (adminUserRepository.count() == 0L) {
            check(config.adminPassword.isNotBlank()) { "首次启动必须设置 ADMIN_PASSWORD" }
            adminUserRepository.create(config.adminUsername, PasswordHasher.hash(config.adminPassword))
            logger.info("created initial admin user {}", config.adminUsername)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        shuttingDown.set(true)
        runBlocking { requestLogWriter.closeAndDrain(5_000) }
        runBlocking { cooldownStateWriter.closeAndDrain(1_000) }
        runBlocking {
            oauthAccountScheduler.flushSnapshots()
            oauthWindowStateWriter.closeAndDrain(2_000)
        }
        runBlocking { logRetentionJob.getAndSet(null)?.cancelAndJoin() }
        runBlocking { keyAutoRecoveryJob.getAndSet(null)?.cancelAndJoin() }
        runBlocking { channelHealthIndexJob?.cancelAndJoin() }
        oauthQuotaService.close()
        oauthTokenService.close()
        scope.cancel()
        iqJudgeService.close()
        iqJudgeRegistry.close()
        accountEgressClients.values.forEach { runCatching { it.close() } }
        accountEgressClients.clear()
        codeAssistClients.close()
        httpClient.close()
        searchHttpClient.close()
        channelQuotaService.close()
        channelQuotaProviderRegistry.close()
        databaseFactory.close()
    }

    companion object {
        val gatewayJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondGatewayError(
    status: HttpStatusCode,
    code: String,
    message: String,
    type: String,
) {
    if (isGeminiRequestPath(request.path())) {
        respondText(
            GatewayApplication.gatewayJson.encodeToString(
                GeminiErrorEnvelope.serializer(), GatewayError(status, code, message, type).toGeminiEnvelope(),
            ), ContentType.Application.Json, status,
        )
        return
    }
    if (request.path() == "/v1/messages") {
        respondText(
            GatewayApplication.gatewayJson.encodeToString(
                AnthropicErrorEnvelope.serializer(),
                AnthropicErrorEnvelope(error = AnthropicErrorBody(anthropicErrorType(status, type), message)),
            ),
            ContentType.Application.Json,
            status,
        )
        return
    }
    respondText(
        GatewayApplication.gatewayJson.encodeToString(
            ErrorEnvelope.serializer(),
            ErrorEnvelope(ErrorBody(message, type, code)),
        ),
        ContentType.Application.Json,
        status,
    )
}
