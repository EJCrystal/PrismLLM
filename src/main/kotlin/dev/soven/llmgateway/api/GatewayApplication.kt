package dev.soven.llmgateway.api

import dev.soven.llmgateway.config.GatewayConfig
import dev.soven.llmgateway.db.DatabaseFactory
import dev.soven.llmgateway.proxy.GatewayMetrics
import dev.soven.llmgateway.proxy.isGeminiRequestPath
import dev.soven.llmgateway.proxy.RequestLogWriter
import dev.soven.llmgateway.repo.ModelPriceRepository
import dev.soven.llmgateway.repo.ModelDefaultPriceRepository
import dev.soven.llmgateway.proxy.ModelPricingService
import dev.soven.llmgateway.proxy.UpstreamModelCatalog
import dev.soven.llmgateway.proxy.ModelPriceReferenceService
import dev.soven.llmgateway.proxy.CurrencyExchangeService
import dev.soven.llmgateway.proxy.UpstreamHeaderCatalogService
import dev.soven.llmgateway.repo.AdminUserRepository
import dev.soven.llmgateway.repo.AdminAuditRepository
import dev.soven.llmgateway.repo.ChannelRepository
import dev.soven.llmgateway.repo.ClientKeyRepository
import dev.soven.llmgateway.repo.ClientKeyQuotaRepository
import dev.soven.llmgateway.repo.CooldownStateRepository
import dev.soven.llmgateway.repo.RequestLogRepository
import dev.soven.llmgateway.repo.RetentionSettingsRepository
import dev.soven.llmgateway.repo.ModelTestSettingsRepository
import dev.soven.llmgateway.repo.UpstreamRequestSettingsRepository
import dev.soven.llmgateway.repo.UpstreamHeaderCatalogRepository
import dev.soven.llmgateway.repo.KeyAutoDisableRepository
import dev.soven.llmgateway.repo.ChannelKeyStateChangedException
import dev.soven.llmgateway.repo.ChannelKeyReenableRequiresCredentialException
import dev.soven.llmgateway.report.LogRetentionService
import dev.soven.llmgateway.quota.ClientKeyQuotaService
import dev.soven.llmgateway.quota.ChannelKeyRateLimiter
import dev.soven.llmgateway.quota.RequestLimitService
import dev.soven.llmgateway.security.AdminSessions
import dev.soven.llmgateway.security.CredentialCipher
import dev.soven.llmgateway.security.PasswordHasher
import dev.soven.llmgateway.plugin.quota.ChannelQuotaProviderRegistry
import dev.soven.llmgateway.plugin.quota.ChannelQuotaService
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
import io.ktor.server.plugins.origin
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
): RequestAccessLogMetadata {
    val peer = sanitizeLogValue(peerIp)
    val candidates = listOf(
        "cf-connecting-ip" to firstHeaderValue(cloudflareConnectingIp),
        "x-forwarded-for" to firstHeaderValue(forwardedFor),
        "x-real-ip" to firstHeaderValue(realIp),
        "forwarded" to forwardedIp(forwarded),
        "peer" to peer.takeUnless { it == "-" },
    )
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

private fun requestAccessLogMetadata(call: io.ktor.server.application.ApplicationCall): RequestAccessLogMetadata =
    requestAccessLogMetadata(
        requestId = call.response.headers[REQUEST_ID_HEADER] ?: call.request.headers[REQUEST_ID_HEADER],
        cloudflareConnectingIp = call.request.headers[CLOUDFLARE_CONNECTING_IP_HEADER],
        forwardedFor = call.request.headers[FORWARDED_FOR_HEADER],
        realIp = call.request.headers[REAL_IP_HEADER],
        forwarded = call.request.headers[FORWARDED_HEADER],
        peerIp = call.request.origin.remoteAddress,
        userAgent = call.request.headers[HttpHeaders.UserAgent],
    )

private fun firstHeaderValue(value: String?): String? =
    value
        ?.split(',')
        ?.asSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.isNotBlank() }
        ?.let { sanitizeLogValue(it) }
        ?.takeUnless { it == "-" }

private fun forwardedIp(value: String?): String? =
    value
        ?.split(',')
        ?.asSequence()
        ?.flatMap { it.split(';').asSequence() }
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("for=", ignoreCase = true) }
        ?.substringAfter('=', "")
        ?.trim()
        ?.trim('"', '[', ']')
        ?.let { sanitizeLogValue(it) }
        ?.takeUnless { it == "-" }

private fun sanitizeLogValue(value: String?, maxLength: Int = 128): String =
    value
        ?.replace(Regex("[\\r\\n\\t]+"), " ")
        ?.trim()
        ?.take(maxLength)
        ?.takeUnless { it.isNullOrBlank() }
        ?: "-"

/**
 * 网关运行时装配。负责数据库迁移、管理员初始化、插件安装和资源生命周期。
 *
 * @author 文杰
 * @since 2026-10-21
 */
class GatewayApplication(val config: GatewayConfig) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(GatewayApplication::class.java)
    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val shuttingDown = AtomicBoolean(false)
    val databaseFactory = DatabaseFactory(config)
    val credentialCipher = CredentialCipher(config.masterKey)
    val channelRepository = ChannelRepository(databaseFactory.database, credentialCipher)
    val clientKeyRepository = ClientKeyRepository(databaseFactory.database)
    val clientKeyQuotaRepository = ClientKeyQuotaRepository(databaseFactory.database)
    val clientKeyQuotaService = ClientKeyQuotaService(clientKeyQuotaRepository)
    val requestLimitService = RequestLimitService(clientKeyQuotaService)
    val adminUserRepository = AdminUserRepository(databaseFactory.database)
    val adminAuditRepository = AdminAuditRepository(databaseFactory.database)
    val keyAutoDisableRepository = KeyAutoDisableRepository(databaseFactory.database, adminAuditRepository)
    val keyAvailabilityGate = KeyAvailabilityGate()
    val channelKeyRateLimiter = ChannelKeyRateLimiter()
    val requestLogRepository = RequestLogRepository(databaseFactory.database)
    val channelHealthRepository = dev.soven.llmgateway.repo.ChannelHealthRepository(databaseFactory.database, config.cooldown.ttftSlowThresholdMs)
    private var channelHealthIndexJob: Job? = null
    val retentionSettingsRepository = RetentionSettingsRepository(databaseFactory.database)
    val modelTestSettingsRepository = ModelTestSettingsRepository(databaseFactory.database)
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
    val requestLogWriter = RequestLogWriter(requestLogRepository, modelPricingService, scope)
    val logRetentionService = LogRetentionService(requestLogRepository, config.retention)
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

    val modelCatalog = UpstreamModelCatalog(channelRepository)
    val modelPriceReferenceService = ModelPriceReferenceService(httpClient)
    val currencyExchangeService = CurrencyExchangeService(httpClient)
    val upstreamHeaderCatalogService = UpstreamHeaderCatalogService(
        httpClient = httpClient,
        repository = upstreamHeaderCatalogRepository,
        refreshIntervalMs = config.upstreamHeaderRefreshIntervalMs,
        autoRefreshEnabled = config.upstreamHeaderRefreshEnabled,
        manifestUrl = config.upstreamHeaderCatalogUrl,
    )
    /** 额度插件仅在管理面查询阶段使用，不参与请求转发。 */
    val channelQuotaProviderRegistry = ChannelQuotaProviderRegistry(config.quotaPluginDir)
    val channelQuotaService = ChannelQuotaService(channelQuotaProviderRegistry, credentialCipher)

    fun install(app: Application) {
        databaseFactory.migrate()
        channelHealthIndexJob = dev.soven.llmgateway.report.startChannelHealthIndexer(requestLogRepository, scope)
        runBlocking { upstreamRequestSettingsRepository.load() }
        runBlocking { upstreamHeaderCatalogService.initialize() }
        runBlocking { seedAdmin() }
        runBlocking { candidateCooldown.restore(cooldownStateRepository.loadAll()) }
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
            exception<ChannelKeyReenableRequiresCredentialException> { call, cause ->
                call.markAdminAuditError("channel_key_reenable_requires_credential")
                call.respondGatewayError(
                    HttpStatusCode.BadRequest,
                    "channel_key_reenable_requires_credential",
                    cause.message ?: "自动禁用的密钥必须更换凭据后才能重新启用",
                    "invalid_request_error",
                )
            }
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
            exception<GatewayException> { call, cause ->
                call.markAdminAuditError(cause.code)
                if (call.response.isCommitted) {
                    val accessLog = requestAccessLogMetadata(call)
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
                if ((call.request.path() == "/v1/messages" || isGeminiRequestPath(call.request.path())) && cause.nativeResponseBody != null) {
                    call.respondText(
                        cause.nativeResponseBody,
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
                val accessLog = requestAccessLogMetadata(call)
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
        runBlocking { logRetentionJob.getAndSet(null)?.cancelAndJoin() }
        runBlocking { keyAutoRecoveryJob.getAndSet(null)?.cancelAndJoin() }
        runBlocking { channelHealthIndexJob?.cancelAndJoin() }
        scope.cancel()
        httpClient.close()
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
                GeminiErrorEnvelope.serializer(), GatewayException(status, code, message, type).toGeminiEnvelope(),
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
