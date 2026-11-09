package dev.soven.llmgateway.integration

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.AppenderBase
import com.sun.net.httpserver.HttpServer
import dev.soven.llmgateway.api.GatewayApplication
import dev.soven.llmgateway.config.GatewayConfig
import dev.soven.llmgateway.config.OAuthConfig
import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.domain.ChannelQuotaSettings
import dev.soven.llmgateway.domain.ChannelQuotaCredentialSource
import dev.soven.llmgateway.domain.ChannelKeyRecord
import dev.soven.llmgateway.domain.ChannelModelRecord
import dev.soven.llmgateway.domain.ChannelKeyRateLimitPolicy
import dev.soven.llmgateway.shared.domain.FailureStage
import dev.soven.llmgateway.shared.domain.GatewayProtocol
import dev.soven.llmgateway.domain.NewIqTestRun
import dev.soven.llmgateway.domain.OAuthAccountPolicy
import dev.soven.llmgateway.domain.OAuthAccountRecord
import dev.soven.llmgateway.domain.OAuthProvider
import dev.soven.llmgateway.domain.RequestLogRecord
import dev.soven.llmgateway.domain.UpstreamRequestOverrides
import dev.soven.llmgateway.domain.UpstreamProtocolOverrides
import dev.soven.llmgateway.domain.UpstreamHeaderProfile
import dev.soven.llmgateway.domain.WebSearchSettingsRecord
import dev.soven.llmgateway.fake.FakeUpstream
import dev.soven.llmgateway.oauth.OAuthAccountDecision
import dev.soven.llmgateway.oauth.OAuthAccountDenyReason
import dev.soven.llmgateway.repo.NewChannelKey
import dev.soven.llmgateway.repo.NewOAuthAccount
import dev.soven.llmgateway.repo.ChannelKeyUpdate
import dev.soven.llmgateway.domain.IqVisionSettingsRecord
import dev.soven.llmgateway.domain.effective
import dev.soven.llmgateway.repo.IqVisionSettingsUpdate
import dev.soven.llmgateway.repo.KeyAutoDisableRepository
import dev.soven.llmgateway.repo.UpstreamRequestSettingsRepository
import dev.soven.llmgateway.repo.UpstreamHeaderCatalogRepository
import dev.soven.llmgateway.proxy.HeaderProfileVersionSource
import dev.soven.llmgateway.proxy.HtmlImageRenderer
import dev.soven.llmgateway.proxy.SvgRenderResult
import dev.soven.llmgateway.proxy.UpstreamHeaderCatalogService
import dev.soven.llmgateway.proxy.UpstreamHeaderProfiles
import dev.soven.llmgateway.resilience.KeyAttemptOutcome
import dev.soven.llmgateway.resilience.KeyAttemptSignal
import dev.soven.llmgateway.resilience.ChannelKeyDisableMode
import dev.soven.llmgateway.resilience.ChannelKeyState
import dev.soven.llmgateway.resilience.FailureKind
import dev.soven.llmgateway.resilience.KeyAutoDisablePolicy
import dev.soven.llmgateway.resilience.KeyDisableAction
import dev.soven.llmgateway.resilience.KeyDisablePolicy
import dev.soven.llmgateway.resilience.KeyDisableRule
import dev.soven.llmgateway.resilience.ResolvedKeyDisablePolicy
import dev.soven.llmgateway.resilience.toKeyDisablePolicy
import dev.soven.llmgateway.shared.crypto.ClientKeyHasher
import dev.soven.llmgateway.quota.ClientKeyQuotaLimits
import dev.soven.llmgateway.quota.ChannelKeyRateLimitDecision
import dev.soven.llmgateway.report.RetentionPolicy
import dev.soven.llmgateway.resilience.CooldownPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.options
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.util.cio.ChannelWriteException
import io.ktor.utils.io.readUTF8Line
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.io.IOException
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * 数据面集成测试：真实 PostgreSQL、假上游、Ktor 测试宿主。
 *
 * @author 文杰
 * @since 2026-10-21
 */

/**
 * 数据面集成测试基类：共享 PostgreSQL 容器（JVM 级只启动一次），每个测试类独享
 * 一个从模板克隆的数据库与一份假上游，从而支持类级别并行与 CI 分片。
 *
 * @author 文杰
 * @since 2026-11-09
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
abstract class GatewayIntegrationTestBase {
    @TempDir
    lateinit var quotaPluginDirectory: Path
    @TempDir
    lateinit var iqJudgePluginDirectory: Path

    protected lateinit var postgres: PostgreSQLContainer<*>
    protected lateinit var databaseUrl: String
    protected lateinit var fake: FakeUpstream
    protected lateinit var app: GatewayApplication

    @BeforeAll
    fun startInfrastructure() {
        postgres = IntegrationPostgres.container
        databaseUrl = IntegrationPostgres.newDatabase()
        fake = FakeUpstream().apply { start() }
    }

    @AfterAll
    fun stopInfrastructure() {
        fake.stop()
    }

    @BeforeEach
    fun setUp() {
        fake.requests.clear()
        fake.manxueRequests.clear()
        fake.modelRequests.clear()
        fake.cancellations.set(0)
        fake.failingModels.clear()
        fake.keyStatuses.clear()
        fake.keyErrorBodies.clear()
        fake.resetQuotaUsage()
        fake.resetOAuthToken()
        fake.clearChatHold()
        app = GatewayApplication(config(), visionRendererOverride = TestVisionRenderer())
        app.databaseFactory.migrate()
        truncateAll()
    }

    @AfterEach
    fun tearDown() {
        if (::app.isInitialized) app.close()
    }

    protected class TestVisionRenderer : HtmlImageRenderer {
        override val label: String = "测试渲染器"

        override fun render(html: String): SvgRenderResult =
            SvgRenderResult.Success(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), 512, 320)

        override fun renderFrames(html: String, times: List<Float>): List<SvgRenderResult> = times.mapIndexed { index, _ ->
            SvgRenderResult.Success(
                byteArrayOf(
                    0x89.toByte(),
                    'P'.code.toByte(),
                    'N'.code.toByte(),
                    'G'.code.toByte(),
                    index.toByte(),
                ),
                512,
                320,
            )
        }
    }

    protected suspend fun assertOversizedSearchResponseRejected(scenario: String) {
        app.webSearchSettingsRepository.save(
            WebSearchSettingsRecord(
                enabled = true,
                provider = "tavily",
                apiKey = "tvly-test",
                baseUrl = "http://127.0.0.1:1",
                maxResults = 3,
                timeoutMs = 2_000,
                maxRounds = 2,
                maxPageChars = 2_000,
                updatedAt = 0,
            ),
        )
        val channel = createChannel(scenario, scenario)
        exposeAsModel(scenario, channel to "gpt-4o-mini")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/responses") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"$scenario","stream":false,"input":"hi","tools":[{"type":"web_search"}]}""")
            }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.BadGateway, response.status, body)
            assertTrue(body.contains("upstream_invalid_response"), "BODY=[$body]")
            assertTrue(body.contains("上限"), "BODY=[$body]")
        }
    }

    protected suspend fun assertSixConverterRoutes(stream: Boolean) {
        val protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES, GatewayProtocol.MESSAGES)
        protocols.forEach { protocol ->
            val upstreamModel = "upstream-"+protocol.value
            val channel = createChannelWithBaseUrl(
                name = "converter-"+protocol.value,
                baseUrl = fake.baseUrl + "/ok/v1",
                protocols = listOf(protocol),
                modelMappings = listOf(ChannelModelRecord(upstreamModel)),
            )
            exposeAsModel("converter-"+protocol.value, channel to upstreamModel)
        }
        val key = createClientKey()
        var count = 0
        testApplication {
            application { app.install(this) }
            protocols.forEach { inbound -> protocols.filter { it != inbound }.forEach { outbound ->
                val model = "converter-"+outbound.value
                val body = when (inbound) {
                    GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.MESSAGES ->
                        """{"model":"$model","stream":$stream,"max_tokens":32,"messages":[{"role":"user","content":"hi"}]}"""
                    GatewayProtocol.RESPONSES -> """{"model":"$model","stream":$stream,"max_output_tokens":32,"input":"hi"}"""
                    else -> error("No Gemini conversion")
                }
                val response = client.post("/v1"+inbound.endpointPath) {
                    header(HttpHeaders.Authorization, "Bearer $key")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                val responseBody = response.bodyAsText()
                assertEquals(HttpStatusCode.OK, response.status, "$inbound → $outbound: $responseBody")
                if (stream || outbound != GatewayProtocol.RESPONSES) assertTrue(responseBody.contains("Hel"), responseBody)
                if (!stream) {
                    val returned = Json.parseToJsonElement(responseBody).jsonObject
                    assertEquals(model, returned["model"]!!.jsonPrimitive.content)
                    val contentField = when (inbound) {
                        GatewayProtocol.CHAT_COMPLETIONS -> "choices"
                        GatewayProtocol.MESSAGES -> "content"
                        GatewayProtocol.RESPONSES -> "output"
                        else -> error("No Gemini conversion")
                    }
                    assertTrue(returned[contentField] is kotlinx.serialization.json.JsonArray, responseBody)
                }
                if (stream) {
                    val terminal = when (inbound) {
                        GatewayProtocol.CHAT_COMPLETIONS -> "[DONE]"
                        GatewayProtocol.MESSAGES -> "message_stop"
                        GatewayProtocol.RESPONSES -> "response.completed"
                        else -> error("No Gemini conversion")
                    }
                    assertTrue(responseBody.contains(terminal), responseBody)
                }
                assertEquals("/ok/v1"+outbound.endpointPath, fake.requests.last().path)
                val upstreamBody = Json.parseToJsonElement(fake.requests.last().body).jsonObject
                assertEquals("upstream-"+outbound.value, upstreamBody["model"]!!.jsonPrimitive.content)
                awaitRequestLogCount(++count)
                val log = app.requestLogRepository.list(10).first { it.alias == model && it.protocol == inbound }
                assertEquals(stream, log.stream)
                assertEquals(outbound, log.upstreamProtocol)
                assertEquals(1, log.upstreamAttempts)
                assertEquals(1, log.attempts.size)
                assertEquals(200, log.attempts.single().statusCode)
                assertEquals(outbound, log.attempts.single().upstreamProtocol)
                assertNull(log.errorCode)
            } }
        }
    }

    protected fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient {
        install(ContentNegotiation) { json() }
    }

    protected suspend fun HttpClient.loginAdmin(
        username: String = "admin",
        password: String = "test-password",
    ): Pair<String, String> {
        val login = post("/admin/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"$password"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val setCookies = login.headers.getAll(HttpHeaders.SetCookie).orEmpty()
        val sessionCookie = setCookies.first { it.startsWith("gw_session=") }.substringBefore(";")
        val csrf = setCookies.first { it.startsWith("gw_csrf=") }.substringAfter("=").substringBefore(";")
        return sessionCookie to csrf
    }

    protected suspend fun HttpClient.assertAdminRequestLogStream(requestId: String, expected: Boolean) {
        val (sessionCookie, csrf) = loginAdmin()
        val response = get("/admin/api/request-logs") {
            header(HttpHeaders.Cookie, sessionCookie)
            header("X-CSRF-Token", csrf)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val loggedStream = Json.parseToJsonElement(response.bodyAsText())
            .jsonArray
            .first { it.jsonObject["requestId"]?.jsonPrimitive?.content == requestId }
            .jsonObject["stream"]
            ?.jsonPrimitive
            ?.content
        assertEquals(expected.toString(), loggedStream)
    }

    protected fun config(
        firstTokenTimeoutMs: Long = 2_000,
        idleTimeoutMs: Long = 2_000,
        totalTimeoutMs: Long = 5_000,
        corsOrigins: List<String> = emptyList(),
        maxAttempts: Int = 2,
        cooldownTransientBaseMs: Long = 3_600_000,
        cooldownRateLimitDefaultMs: Long = 60_000,
        cooldownRateLimitMaxMs: Long = 60_000,
        ttftSlowThresholdMs: Long = 600_000,
        ttftSlowStreak: Int = 100,
        keyAutoRecoveryIntervalMs: Long = 3_600_000,
    ): GatewayConfig = GatewayConfig(
        port = 0,
        databaseUrl = databaseUrl,
        databaseUser = postgres.username,
        databasePassword = postgres.password,
        masterKey = ByteArray(32) { 7 },
        adminUsername = "admin",
        adminPassword = "test-password",
        corsOrigins = corsOrigins,
        requestBodyLimitBytes = 1024 * 1024,
        connectTimeoutMs = 1_000,
        firstTokenTimeoutMs = firstTokenTimeoutMs,
        idleTimeoutMs = idleTimeoutMs,
        totalTimeoutMs = totalTimeoutMs,
        maxAttempts = maxAttempts,
        drainTimeoutMs = 1_000,
        staticDir = Path.of("web/dist"),
        quotaPluginDir = quotaPluginDirectory,
        manxueEnabled = true,
        manxueApiBaseUrl = fake.baseUrl + "/manxue",
        manxueTimeoutMs = 5_000,
        iqDefaultJudge = "local",
        iqJudgeTimeoutMs = 5_000,
        iqJudgePluginDir = iqJudgePluginDirectory,
        iqVisionEnabled = true,
        iqVisionBaseUrl = fake.baseUrl + "/vision/v1",
        iqVisionModel = "vision-test",
        iqVisionTimeoutMs = 5_000,
        iqVisionRenderWidth = 512,
        cookieSecure = false,
        retention = RetentionPolicy(cleanupEnabled = false),
        cooldown = CooldownPolicy(
            transientBaseMs = cooldownTransientBaseMs,
            transientMaxMs = 3_600_000,
            rateLimitDefaultMs = cooldownRateLimitDefaultMs,
            rateLimitMaxMs = cooldownRateLimitMaxMs,
            authFailureMs = 600_000,
            ttftSlowThresholdMs = ttftSlowThresholdMs,
            ttftSlowStreak = ttftSlowStreak,
        ),
        keyAutoRecoveryIntervalMs = keyAutoRecoveryIntervalMs,
        upstreamHeaderRefreshEnabled = false,
        upstreamHeaderRefreshIntervalMs = 3_600_000,
        // 订阅账号的 token 刷新与额度查询都指向假上游，测试不访问真实 OAuth 端点。
        oauth = OAuthConfig(
            chatGpt = OAuthConfig().chatGpt.copy(
                tokenEndpoint = fake.baseUrl + "/oauth/token",
                usageUrl = fake.baseUrl + "/oauth/usage",
            ),
            antigravity = OAuthConfig().antigravity.copy(
                tokenEndpoint = fake.baseUrl + "/oauth/token",
            ),
        ),
    )

    protected fun requestLog(requestId: String, createdAt: Long) = RequestLogRecord(
        requestId = requestId,
        clientKeyId = null,
        clientKeyName = null,
        alias = "fast-chat",
        channelId = null,
        channelName = null,
        upstreamModel = null,
        protocol = GatewayProtocol.CHAT_COMPLETIONS,
        statusCode = 200,
        firstTokenMs = null,
        totalMs = 1,
        inputTokens = 1,
        outputTokens = 1,
        cachedTokens = null,
        reasoningTokens = null,
        reasoningEffort = null,
        totalTokens = 2,
        usageKnown = true,
        costAmount = null,
        costCurrency = null,
        inputPricePerMillion = null,
        outputPricePerMillion = null,
        cachedInputPricePerMillion = null,
        errorCode = null,
        failureStage = null,
        errorMessage = null,
        upstreamErrorCode = null,
        createdAt = createdAt,
    )

    protected suspend fun awaitRequestLogCount(count: Int, timeoutMs: Long = 3_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (app.requestLogRepository.list(10).size >= count) return
            delay(50)
        }
        error("expected $count request logs within ${timeoutMs}ms")
    }

    protected suspend fun createAutoDisablePool(withBackup: Boolean = true): ChannelRecord = app.channelRepository.create(
        name = "auto-disable-pool",
        baseUrl = "${fake.baseUrl}/key-health/v1",
        keys = listOf(NewChannelKey("primary", app.credentialCipher.encrypt("key-primary"), models = listOf("m1", "m2"))) +
            if (withBackup) listOf(NewChannelKey("backup", app.credentialCipher.encrypt("key-backup"), priority = 2, models = listOf("m1", "m2")))
            else emptyList(),
        authType = "bearer", protocols = GatewayProtocol.entries.toList(), enabled = true, modelMappings = mappingsOf("m1", "m2"),
    )

    protected suspend fun HttpClient.sendAutoDisableRequest(
        key: String,
        model: String = "m1",
        path: String = "/v1/chat/completions",
        requestId: String = java.util.UUID.randomUUID().toString(),
    ) = post(path) {
        header(HttpHeaders.Authorization, "Bearer $key")
        header("X-Request-Id", requestId)
        contentType(ContentType.Application.Json)
        setBody("""{"model":"$model","messages":[{"role":"user","content":"private-test-prompt"}],"max_tokens":8}""")
    }

    protected suspend fun createChannel(name: String, scenario: String): ChannelRecord {
        return createChannelWithBaseUrl(name, "${fake.baseUrl}/$scenario/v1")
    }

    protected suspend fun createChannelWithBaseUrl(
        name: String,
        baseUrl: String,
        protocols: List<GatewayProtocol> = GatewayProtocol.default,
        modelMappings: List<ChannelModelRecord> = mappingsOf("gpt-4o-mini", "deepseek-chat", "m1", "m2"),
        priority: Int = 0,
        authType: String = "bearer",
        requestOverrides: UpstreamRequestOverrides = UpstreamRequestOverrides(),
    ): ChannelRecord {
        val encrypted = app.credentialCipher.encrypt("upstream-secret")
        return app.channelRepository.create(
            name = name,
            baseUrl = baseUrl,
            keys = listOf(
                dev.soven.llmgateway.repo.NewChannelKey(
                    name = "默认密钥",
                    credential = encrypted,
                    models = modelMappings.map { it.upstreamModel },
                ),
            ),
            authType = authType,
            protocols = protocols,
            enabled = true,
            modelMappings = modelMappings,
            priority = priority,
            requestOverrides = requestOverrides,
        )
    }

    protected fun mappingsOf(vararg models: String): List<ChannelModelRecord> =
        models.map { ChannelModelRecord(upstreamModel = it) }

    protected suspend fun createOAuthAccount(
        channel: ChannelRecord,
        email: String,
        refreshToken: String = "oauth-refresh-token",
        provider: OAuthProvider = OAuthProvider.CHATGPT,
    ): OAuthAccountRecord = app.oauthAccountRepository.create(
        NewOAuthAccount(
            name = email,
            provider = provider,
            accountEmail = email,
            refreshToken = app.credentialCipher.encrypt(refreshToken),
            channelIds = listOf(channel.id),
        ),
    )

    protected suspend fun createChannelWithProtocols(
        name: String,
        scenario: String,
        protocols: List<GatewayProtocol>,
    ): ChannelRecord = createChannelWithBaseUrl(name, "${fake.baseUrl}/$scenario/v1", protocols)

    /**
     * 把给定渠道的上游模型映射到同一个对外模型 ID，等价于旧测试里“建一个同名别名”。
     * 现在只有直连暴露，跨渠道聚合靠渠道模型映射的 canonical ID。
     */

    protected suspend fun exposeAsModel(publicId: String, vararg candidates: Pair<ChannelRecord, String>) {
        exposeUnderCanonicalId(publicId, candidates.map { it.first.id to it.second })
    }

    /** 同 [exposeAsModel]；档位由渠道优先级决定，候选里的 position 不再生效。 */

    protected suspend fun exposeUnderCanonicalIdInOrder(publicId: String, vararg candidates: Triple<ChannelRecord, String, Int>) {
        exposeUnderCanonicalId(publicId, candidates.map { it.first.id to it.second })
    }

    protected suspend fun exposeUnderCanonicalId(publicId: String, mappings: List<Pair<String, String>>) {
        val byChannel = mappings.groupBy({ it.first }, { it.second })
        byChannel.forEach { (channelId, upstreamModels) ->
            val channel = assertNotNull(app.channelRepository.find(channelId))
            val targets = upstreamModels.toSet()
            val updatedMappings = channel.modelMappings.map { mapping ->
                if (mapping.upstreamModel in targets) mapping.copy(canonicalModelId = publicId) else mapping
            }
            app.channelRepository.update(
                id = channel.id,
                name = channel.name,
                baseUrl = channel.baseUrl,
                authType = channel.authType,
                protocols = channel.protocols,
                enabled = channel.enabled,
                modelMappings = updatedMappings,
                priority = channel.priority,
            )
        }
    }

    protected suspend fun createClientKey(
        quotas: ClientKeyQuotaLimits = ClientKeyQuotaLimits(),
    ): String {
        val generated = ClientKeyHasher.generate()
        app.clientKeyRepository.create(
            name = "test-key",
            keyHash = generated.hash,
            keyPrefix = generated.prefix,
            enabled = true,
            expiresAt = null,
            quotas = quotas,
        )
        return generated.raw
    }

    protected suspend fun createPrice(
        channel: ChannelRecord,
        upstreamModel: String,
        input: String,
        output: String,
        cached: String? = null,
    ) = checkNotNull(
        app.modelPriceRepository.create(
            channelId = channel.id,
            upstreamModel = upstreamModel,
            currency = "USD",
            inputPricePerMillion = BigDecimal(input),
            outputPricePerMillion = BigDecimal(output),
            cachedInputPricePerMillion = cached?.let(::BigDecimal),
        ),
    )

    protected suspend fun createDefaultPrice(
        modelId: String,
        input: String,
        output: String,
        cached: String? = null,
    ) = app.modelDefaultPriceRepository.create(
        modelId = modelId,
        currency = "USD",
        inputPricePerMillion = BigDecimal(input),
        outputPricePerMillion = BigDecimal(output),
        cachedInputPricePerMillion = cached?.let(::BigDecimal),
    )

    protected suspend fun awaitRequestLog(timeoutMs: Long = 3_000): RequestLogRecord {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val logs = app.requestLogRepository.list(10)
            if (logs.isNotEmpty()) return logs.first()
            delay(50)
        }
        error("request log not written within ${timeoutMs}ms")
    }

    protected suspend fun awaitCondition(
        description: String,
        timeoutMs: Long = 3_000,
        condition: suspend () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(10)
        }
        error("timed out waiting for $description within ${timeoutMs}ms")
    }

    protected suspend fun awaitLogWithEffort(effort: String, timeoutMs: Long = 3_000): RequestLogRecord {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val log = app.requestLogRepository.list(10).firstOrNull { it.reasoningEffort == effort }
            if (log != null) return log
            delay(50)
        }
        error("request log with reasoning effort $effort not written within ${timeoutMs}ms")
    }

    protected suspend fun awaitCancellationWithin(timeoutMs: Long = 3_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (fake.cancellations.get() > 0) return
            delay(50)
        }
        error("upstream request was not cancelled within ${timeoutMs}ms")
    }

    protected fun truncateAll() {
        DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use {
                it.execute(
                    "truncate table cooldown_state, admin_audit_logs, retention_settings, model_test_settings, upstream_request_settings, upstream_header_catalog_state, upstream_header_profile_versions, upstream_header_profile_pins, key_auto_disable_settings, request_logs, model_default_prices, model_prices, client_key_quota_usage, client_keys, channels, admin_users, web_search_settings, iq_vision_settings, iq_test_runs restart identity cascade",
                )
            }
        }
    }

    protected fun scalarInt(sql: String): Int =
        DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    check(result.next()) { "query returned no rows: $sql" }
                    result.getInt(1)
                }
            }
        }
}
