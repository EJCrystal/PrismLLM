package dev.soven.llmgateway.api

import dev.soven.llmgateway.config.GatewayConfig
import dev.soven.llmgateway.db.DatabaseFactory
import dev.soven.llmgateway.proxy.GatewayMetrics
import dev.soven.llmgateway.proxy.RequestLogWriter
import dev.soven.llmgateway.repo.ModelPriceRepository
import dev.soven.llmgateway.proxy.UpstreamModelCatalog
import dev.soven.llmgateway.proxy.ModelPriceReferenceService
import dev.soven.llmgateway.proxy.CurrencyExchangeService
import dev.soven.llmgateway.repo.AdminUserRepository
import dev.soven.llmgateway.repo.AdminAuditRepository
import dev.soven.llmgateway.repo.AliasRepository
import dev.soven.llmgateway.repo.ChannelRepository
import dev.soven.llmgateway.repo.ClientKeyRepository
import dev.soven.llmgateway.repo.ClientKeyQuotaRepository
import dev.soven.llmgateway.repo.RequestLogRepository
import dev.soven.llmgateway.repo.TeamLimitRepository
import dev.soven.llmgateway.report.LogRetentionService
import dev.soven.llmgateway.quota.ClientKeyQuotaService
import dev.soven.llmgateway.quota.RequestLimitService
import dev.soven.llmgateway.security.AdminSessions
import dev.soven.llmgateway.security.CredentialCipher
import dev.soven.llmgateway.security.PasswordHasher
import dev.soven.llmgateway.resilience.UpstreamCircuitBreaker
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
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

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
    val channelRepository = ChannelRepository(databaseFactory.database)
    val aliasRepository = AliasRepository(databaseFactory.database)
    val clientKeyRepository = ClientKeyRepository(databaseFactory.database)
    val clientKeyQuotaRepository = ClientKeyQuotaRepository(databaseFactory.database)
    val clientKeyQuotaService = ClientKeyQuotaService(clientKeyQuotaRepository)
    val teamLimitRepository = TeamLimitRepository(databaseFactory.database)
    val requestLimitService = RequestLimitService(clientKeyQuotaService, teamLimitRepository)
    val adminUserRepository = AdminUserRepository(databaseFactory.database)
    val adminAuditRepository = AdminAuditRepository(databaseFactory.database)
    val requestLogRepository = RequestLogRepository(databaseFactory.database)
    val modelPriceRepository = ModelPriceRepository(databaseFactory.database)
    val credentialCipher = CredentialCipher(config.masterKey)
    val sessions = AdminSessions()
    val upstreamCircuitBreaker = UpstreamCircuitBreaker(config.resilience)
    val metrics = GatewayMetrics()
    val requestLogWriter = RequestLogWriter(requestLogRepository, modelPriceRepository, scope)
    val logRetentionService = LogRetentionService(requestLogRepository, config.retention)
    private val logRetentionJob = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Job?>(null)

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

    fun install(app: Application) {
        databaseFactory.migrate()
        runBlocking { seedAdmin() }

        app.install(ContentNegotiation) {
            json(gatewayJson)
        }
        app.install(StatusPages) {
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
                    logger.warn("gateway error after response committed, code={}", cause.code)
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
                    call.respondText(
                        gatewayJson.encodeToString(
                            ErrorEnvelope.serializer(),
                            ErrorEnvelope(ErrorBody("网关内部错误", "api_error", "internal_error")),
                        ),
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError,
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
            filter { call -> call.request.path().startsWith("/v1/") }
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
        logRetentionJob.compareAndSet(null, logRetentionService.start(scope))
    }

    fun beginShutdown() {
        shuttingDown.set(true)
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
        logRetentionJob.getAndSet(null)?.cancel()
        scope.cancel()
        httpClient.close()
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
    respondText(
        GatewayApplication.gatewayJson.encodeToString(
            ErrorEnvelope.serializer(),
            ErrorEnvelope(ErrorBody(message, type, code)),
        ),
        ContentType.Application.Json,
        status,
    )
}
