package dev.soven.prismllm.integration

import dev.soven.prismllm.config.GatewayConfig
import dev.soven.prismllm.quota.domain.ClientKeyQuotaLimits
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 真正的 Java Main 子进程与 Linux SIGTERM；不触碰测试以外的数据库或进程。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
@Tag("shard-4")
@EnabledOnOs(OS.LINUX)
@Timeout(60)
class GracefulShutdownIntegrationTest : GatewayIntegrationTestBase() {
    @TempDir
    lateinit var childDirectory: Path

    @Test
    fun `SIGTERM makes readiness unavailable while an in flight stream drains and its request log is persisted`() = runBlocking {
        val firstClientFrame = CompletableDeferred<Unit>()
        val firstUpstreamFrame = CompletableDeferred<Unit>()
        val finishUpstream = CompletableDeferred<Unit>()
        val relay = HttpClient(CIO) {
            install(HttpTimeout) { requestTimeoutMillis = 15_000 }
        }
        // FakeUpstream 的正常流只有几十毫秒。这个仅本测试使用的本机转发器在首帧后设屏障，
        // 保留它的真实内容/用量，直到 SIGTERM、readiness 探测和至少一秒在途窗口均已观察到。
        val upstream = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            routing {
                post("/v1/chat/completions") {
                    val requestBody = call.receiveText()
                    val authorization = call.request.headers[HttpHeaders.Authorization]
                    relay.preparePost("${fake.baseUrl}/ok/v1/chat/completions") {
                        authorization?.let { header(HttpHeaders.Authorization, it) }
                        contentType(ContentType.Application.Json)
                        setBody(requestBody)
                    }.execute { response ->
                        check(response.status == HttpStatusCode.OK)
                        val body = response.bodyAsChannel()
                        call.respondBytesWriter(ContentType.Text.EventStream) {
                            var held = false
                            while (true) {
                                val line = body.readUTF8Line() ?: break
                                writeFully((line + "\n").toByteArray(Charsets.UTF_8))
                                flush()
                                if (!held && line.isEmpty()) {
                                    held = true
                                    firstUpstreamFrame.complete(Unit)
                                    withTimeout(15_000) { finishUpstream.await() }
                                }
                            }
                        }
                    }
                }
            }
        }
        val http = HttpClient(CIO) {
            install(HttpTimeout) {
                connectTimeoutMillis = 1_000
                requestTimeoutMillis = 12_000
                socketTimeoutMillis = 12_000
            }
        }
        val health = HttpClient(CIO) {
            install(HttpTimeout) {
                connectTimeoutMillis = 150
                requestTimeoutMillis = 300
                socketTimeoutMillis = 300
            }
        }
        var child: Process? = null
        val childOutput = childDirectory.resolve("gateway-child.log")
        try {
            upstream.start(wait = false)
            val upstreamPort = upstream.engine.resolvedConnectors().single().port
            val channel = createChannelWithBaseUrl("sigterm-upstream", "http://127.0.0.1:$upstreamPort/v1")
            exposeAsModel(MODEL, channel to "gpt-4o-mini")
            createPrice(channel, "gpt-4o-mini", input = "1", output = "2")
            val key = createClientKey(ClientKeyQuotaLimits(maxConcurrentRequests = 1))
            val keyId = app.clientKeyRepository.list().single().id
            // 使用基类同一把随机 masterKey 解密刚刚写入的假上游凭据，绝不读取 .env。
            val childConfig = config(firstTokenTimeoutMs = 5_000, idleTimeoutMs = 6_000, totalTimeoutMs = 10_000).copy(
                port = freeLoopbackPort(),
                drainTimeoutMs = DRAIN_TIMEOUT_MS,
                staticDir = childDirectory.resolve("static"),
                manxueEnabled = false,
                iqVisionEnabled = false,
            )
            val process = startChild(childConfig, childOutput)
            child = process
            assertTrue(process.supportsNormalTermination(), "Linux destroy() must send SIGTERM rather than forcibly kill the child")
            val baseUrl = "http://127.0.0.1:${childConfig.port}"
            awaitCondition("child Main readiness", timeoutMs = 30_000) {
                assertTrue(process.isAlive, "child exited before readiness")
                val ready = probeReadiness(health, baseUrl)
                ready.status == HttpStatusCode.OK && ready.body == "ok"
            }
            val requestId = UUID.randomUUID().toString()
            val stream = async {
                http.preparePost("$baseUrl/v1/chat/completions") {
                    header(HttpHeaders.Authorization, "Bearer $key")
                    header("X-Request-Id", requestId)
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"$MODEL","stream":true,"messages":[{"role":"user","content":"hi"}]}""")
                }.execute { response ->
                    val body = response.bodyAsChannel()
                    val received = StringBuilder()
                    while (true) {
                        val line = body.readUTF8Line() ?: break
                        received.append(line).append('\n')
                        if (line.startsWith("data:") && line.contains("\"content\":\"Hel\"")) {
                            firstClientFrame.complete(Unit)
                        }
                    }
                    StreamResult(response.status, received.toString(), System.nanoTime())
                }
            }
            try {
                withTimeout(8_000) {
                    firstUpstreamFrame.await()
                    firstClientFrame.await()
                }
                assertEquals(1, fake.requests.size)
                assertFalse(stream.isCompleted, "a complete stream cannot verify draining")
                assertEquals(HttpStatusCode.OK, probeReadiness(health, baseUrl).status)

                val signalledAt = System.nanoTime()
                process.destroy() // UnixProcess.destroy() 在 Linux/WSL 上发送实际 SIGTERM。
                awaitCondition("readiness unavailable before the held stream finishes", timeoutMs = 2_000) {
                    assertTrue(process.isAlive, "the child must stay alive to drain the held stream")
                    assertFalse(stream.isCompleted, "the stream must still be in flight while readiness changes")
                    val ready = probeReadiness(health, baseUrl)
                    when (ready.status) {
                        HttpStatusCode.ServiceUnavailable -> {
                            assertEquals("shutting down", ready.body)
                            true
                        }
                        null -> true // Netty 也可以先关闭监听 socket；连接失败同样表示不能继续接流量。
                        else -> false
                    }
                }
                assertTrue(process.isAlive)
                val rejected = http.post("$baseUrl/v1/chat/completions") {
                    header(HttpHeaders.Authorization, "Bearer $key")
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"$MODEL","messages":[]}""")
                }
                assertEquals(HttpStatusCode.ServiceUnavailable, rejected.status)
                assertTrue(rejected.bodyAsText().contains("gateway_shutting_down"))
                assertEquals(1, fake.requests.size, "shutdown rejects new requests without calling upstream")
                // 有界同步窗口，不依赖启动/停机时序猜测或一个长 sleep；首帧已确实到达客户端。
                awaitCondition("one second of observed in flight draining", timeoutMs = 2_000) {
                    assertTrue(process.isAlive, "child exited instead of draining")
                    assertFalse(stream.isCompleted)
                    assertTrue(probeReadiness(health, baseUrl).status != HttpStatusCode.OK, "readiness became available again during drain")
                    elapsedMillis(signalledAt) >= MIN_STREAM_DURATION_MS
                }
                finishUpstream.complete(Unit)
                val result = withTimeout(DRAIN_TIMEOUT_MS) { stream.await() }
                assertEquals(HttpStatusCode.OK, result.status, result.body)
                assertTrue(result.body.contains("\"content\":\"Hel\"") && result.body.contains("\"content\":\"lo\""), result.body)
                assertTrue(result.body.contains("[DONE]"), "the stream must end normally during drain: ${result.body}")
                val streamElapsedMs = TimeUnit.NANOSECONDS.toMillis(result.completedAt - signalledAt)
                assertTrue(streamElapsedMs in MIN_STREAM_DURATION_MS..DRAIN_TIMEOUT_MS, "stream completed after ${streamElapsedMs}ms with ${DRAIN_TIMEOUT_MS}ms drain budget")
                assertTrue(process.waitFor(CHILD_EXIT_BUDGET_MS, TimeUnit.MILLISECONDS), "child did not exit within its bounded shutdown budget")
                assertFalse(process.isAlive)
                assertEquals(143, process.exitValue(), "a real Linux SIGTERM exit is 128 + 15")

                // 子进程退出之后再读 PG，证明 closeAndDrain 确实完成落库，不只是排进内存队列。
                val log = awaitRequestLog(timeoutMs = 2_000)
                assertEquals(requestId, log.requestId)
                assertEquals(keyId, log.clientKeyId)
                assertEquals(200, log.statusCode)
                assertEquals(true, log.stream)
                assertNull(log.errorCode)
                assertNull(log.failureStage)
                assertEquals(13L, log.totalTokens)
                assertEquals("USD", log.costCurrency)
                val usage = app.clientKeyQuotaService.currentUsage(keyId)
                assertEquals(13L, usage.dailyTokens)
                assertEquals(0, usage.dailyUsdCost.compareTo(assertNotNull(log.costAmount)))
                assertEquals(1, log.upstreamAttempts)
                assertEquals(1, scalarInt("select count(*) from request_logs where request_id = ?", requestId))
                assertEquals(1, fake.requests.size, "SIGTERM must not replay upstream traffic")
                assertEquals(0, fake.cancellations.get())
                assertTrue(probeReadiness(health, baseUrl).status != HttpStatusCode.OK, "readiness must remain unavailable after exit")
            } finally {
                finishUpstream.complete(Unit)
                stream.cancel()
            }
        } catch (failure: Throwable) {
            if (Files.exists(childOutput)) {
                failure.addSuppressed(IllegalStateException("Test child output:\n" + Files.readString(childOutput).takeLast(16_000)))
            }
            throw failure
        } finally {
            finishUpstream.complete(Unit)
            child?.let(::stopOnlyThisChild)
            http.close()
            health.close()
            relay.close()
            runCatching { upstream.stop(0, 1_000) }
        }
    }

    private fun startChild(config: GatewayConfig, output: Path): Process {
        require(config.databaseUrl == databaseUrl)
        require(databaseUrl.substringBefore('?').substringAfterLast('/').matches(Regex("prism_test_[0-9]+"))) {
            "the child may only use this class's IntegrationPostgres clone"
        }
        // Surefire 的 java.class.path 可能只有 booter JAR；完整测试 classpath 在这个属性中。
        // 子进程工作目录隔离后，相对条目必须在父进程里先转为绝对路径。
        val rawClasspath = System.getProperty("surefire.test.class.path")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("java.class.path")
        val classpathDirectory = Files.createDirectory(childDirectory.resolve("classpath"))
        val classpath = rawClasspath.split(File.pathSeparatorChar).filter { it.isNotBlank() }
            .map { Path.of(it).toAbsolutePath().normalize() }
            .mapIndexed { index, source ->
                // 新 JVM 不经过父进程的类缓存；WSL 挂载盘冷读不能消耗停机验收的启动预算。
                val destination = classpathDirectory.resolve("$index-${source.fileName}")
                if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                    require(source.fileName.toString() in setOf("classes", "test-classes") && source.parent.fileName.toString() == "target") {
                        "only compiled class directories belong in the child classpath"
                    }
                    Files.walk(source).use { paths ->
                        paths.forEach { entry ->
                            val target = destination.resolve(source.relativize(entry))
                            if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                                Files.createDirectory(target)
                            } else {
                                require(Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))
                                Files.copy(entry, target)
                            }
                        }
                    }
                } else {
                    require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) && source.fileName.toString().endsWith(".jar"))
                    Files.copy(source, destination)
                }
                destination.toString()
            }.joinToString(File.pathSeparator)
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().toString()
        val builder = ProcessBuilder(java, "-cp", classpath, "dev.soven.prismllm.MainKt")
            .directory(childDirectory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
        builder.environment().apply {
            // 白名单重建环境：不继承数据库、API/OAuth 密钥、代理或 JAVA_TOOL_OPTIONS 等用户值。
            clear()
            putAll(
                mapOf(
                    "HOME" to childDirectory.toAbsolutePath().toString(),
                    "TMPDIR" to childDirectory.toAbsolutePath().toString(),
                    "LANG" to "C.UTF-8",
                    "TZ" to "UTC",
                    "PORT" to config.port.toString(),
                    "DATABASE_URL" to config.databaseUrl,
                    "DATABASE_USER" to config.databaseUser,
                    "DATABASE_PASSWORD" to config.databasePassword,
                    "GATEWAY_MASTER_KEY" to Base64.getEncoder().encodeToString(config.masterKey),
                    "ADMIN_USERNAME" to config.adminUsername,
                    "ADMIN_PASSWORD" to config.adminPassword,
                    "CONNECT_TIMEOUT_MS" to config.connectTimeoutMs.toString(),
                    "FIRST_TOKEN_TIMEOUT_MS" to config.firstTokenTimeoutMs.toString(),
                    "IDLE_TIMEOUT_MS" to config.idleTimeoutMs.toString(),
                    "TOTAL_TIMEOUT_MS" to config.totalTimeoutMs.toString(),
                    "MAX_ATTEMPTS" to config.maxAttempts.toString(),
                    "DRAIN_TIMEOUT_MS" to config.drainTimeoutMs.toString(),
                    "STATIC_DIR" to config.staticDir.toAbsolutePath().toString(),
                    "QUOTA_PLUGIN_DIR" to config.quotaPluginDir.toAbsolutePath().toString(),
                    "IQ_JUDGE_PLUGIN_DIR" to config.iqJudgePluginDir.toAbsolutePath().toString(),
                    "IQ_VISION_BROWSER_PATH" to childDirectory.resolve("no-test-browser").toAbsolutePath().toString(),
                    "COOKIE_SECURE" to "false",
                    "REQUEST_LOG_CLEANUP_ENABLED" to "false",
                    "UPSTREAM_HEADER_REFRESH_ENABLED" to "false",
                    "KEY_AUTO_RECOVERY_INTERVAL_MS" to config.keyAutoRecoveryIntervalMs.toString(),
                    "MANXUE_ENABLED" to "false",
                    "IQ_VISION_ENABLED" to "false",
                    "WEB_SEARCH_ENABLED" to "false",
                ),
            )
        }
        return builder.start()
    }

    private suspend fun probeReadiness(http: HttpClient, baseUrl: String): ReadinessProbe = try {
        val response = http.get("$baseUrl/health/ready")
        ReadinessProbe(response.status, response.bodyAsText())
    } catch (cause: CancellationException) {
        throw cause
    } catch (_: Exception) {
        ReadinessProbe(null, null)
    }

    private fun stopOnlyThisChild(child: Process) {
        if (!child.isAlive) return
        child.destroy()
        try {
            if (!child.waitFor(2, TimeUnit.SECONDS)) {
                child.destroyForcibly()
                child.waitFor(3, TimeUnit.SECONDS)
            }
        } catch (_: InterruptedException) {
            child.destroyForcibly()
            Thread.currentThread().interrupt()
        }
    }

    private fun freeLoopbackPort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    private fun elapsedMillis(startedAt: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private data class ReadinessProbe(val status: HttpStatusCode?, val body: String?)
    private data class StreamResult(val status: HttpStatusCode, val body: String, val completedAt: Long)

    companion object {
        private const val MODEL = "sigterm-chat"
        private const val DRAIN_TIMEOUT_MS = 3_000L
        private const val MIN_STREAM_DURATION_MS = 1_000L
        // Main.stop 的额外 5 秒和 requestLogWriter/后台状态 drain 预算，均只等待该子进程。
        private const val CHILD_EXIT_BUDGET_MS = DRAIN_TIMEOUT_MS + 5_000 + 8_000
    }
}
