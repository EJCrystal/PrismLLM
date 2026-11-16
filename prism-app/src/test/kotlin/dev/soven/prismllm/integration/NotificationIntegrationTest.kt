package dev.soven.prismllm.integration

import dev.soven.prismllm.admin.domain.NotificationEvent
import dev.soven.prismllm.admin.domain.NotificationEvents
import dev.soven.prismllm.admin.domain.NotificationTarget
import dev.soven.prismllm.api.GatewayApplication
import dev.soven.prismllm.channel.domain.ChannelModelRecord
import dev.soven.prismllm.channel.domain.ChannelRecord
import dev.soven.prismllm.channel.domain.key.ChannelKeyState
import dev.soven.prismllm.channel.domain.key.KeyAttemptSignal
import dev.soven.prismllm.channel.domain.key.KeyDisableAction
import dev.soven.prismllm.channel.domain.key.KeyDisablePolicy
import dev.soven.prismllm.channel.domain.key.KeyDisableRule
import dev.soven.prismllm.channel.persistence.ChannelKeyUpdate
import dev.soven.prismllm.channel.persistence.ChannelRepository
import dev.soven.prismllm.channel.persistence.KeyStateObserver
import dev.soven.prismllm.channel.persistence.NewChannelKey
import dev.soven.prismllm.notification.NotificationSecrets
import dev.soven.prismllm.notification.NotificationSendResult
import dev.soven.prismllm.notification.NotificationTransport
import dev.soven.prismllm.notification.notificationEvent
import dev.soven.prismllm.shared.domain.GatewayProtocol
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("integration")
@Tag("channel")
@Tag("shard-4")
class NotificationIntegrationTest : GatewayIntegrationTestBase() {
    private val sent = CopyOnWriteArrayList<NotificationEvent>()
    private var upstreamCredential = ""

    private fun replaceApp(result: NotificationSendResult = NotificationSendResult(success = true, retryable = false)) {
        app.close()
        sent.clear()
        val url = "https://receiver.invalid/${UUID.randomUUID()}"
        app = GatewayApplication(
            config(maxAttempts = 1),
            notificationSecrets = NotificationSecrets { if (it == "PRISM_NOTIFY_TEST_URL") url else null },
            notificationTransport = NotificationTransport { _, event -> sent.add(event); result },
        )
    }

    private suspend fun channel(): ChannelRecord {
        upstreamCredential = UUID.randomUUID().toString()
        return app.channelRepository.create(
            name = "通知测试渠道", baseUrl = "${fake.baseUrl}/key-health/v1",
            keys = listOf(NewChannelKey("测试密钥", app.credentialCipher.encrypt(upstreamCredential), models = listOf("m1"))),
            authType = "bearer", protocols = GatewayProtocol.default, enabled = true,
            modelMappings = listOf(ChannelModelRecord("m1")),
        )
    }

    private suspend fun target(events: List<String>): NotificationTarget {
        app.notificationRuntime.updateSettings(true, 2)
        return app.notificationRepository.saveTarget(NotificationTarget(
            id = UUID.randomUUID().toString(), name = "测试接收端", type = "webhook", events = events,
            environmentPrefix = "PRISM_NOTIFY_TEST", revision = 0,
        ))
    }

    private suspend fun setState(channel: ChannelRecord, state: ChannelKeyState, repository: ChannelRepository = app.channelRepository) {
        repository.update(
            id = channel.id, name = channel.name, baseUrl = channel.baseUrl, authType = channel.authType,
            enabled = true, keys = listOf(ChannelKeyUpdate(
                id = channel.keys.single().id, name = "测试密钥", models = listOf("m1"), state = state,
            )),
        )
    }

    @Test
    fun `manual key transitions persist one event each and deliver asynchronously`() = runBlocking {
        replaceApp()
        val channel = channel()
        val target = target(listOf(NotificationEvents.KEY_DISABLED, NotificationEvents.KEY_RECOVERED))
        setState(channel, ChannelKeyState.MANUAL_DISABLED)
        setState(channel, ChannelKeyState.MANUAL_DISABLED)
        setState(channel, ChannelKeyState.ACTIVE)
        assertEquals(2L, app.notificationRepository.deliveries(target.id).total)
        assertTrue(sent.isEmpty(), "状态事务不能执行网络投递")
        repeat(2) { app.notificationRuntime.deliverDue() }
        assertEquals(setOf(NotificationEvents.KEY_DISABLED, NotificationEvents.KEY_RECOVERED), sent.map { it.eventType }.toSet())
        assertTrue(app.notificationRepository.deliveries(target.id).items.all { it.status == "succeeded" })
        assertTrue(sent.all { !Json.encodeToString(NotificationEvent.serializer(), it).contains(upstreamCredential) })
    }

    @Test
    fun `automatic temporary disable and expiry recovery create transactional events`() = runBlocking {
        replaceApp()
        val channel = channel()
        val target = target(listOf(NotificationEvents.KEY_DISABLED, NotificationEvents.KEY_RECOVERED))
        app.keyAutoDisableRepository.saveSettings(KeyDisablePolicy(rules = listOf(
            KeyDisableRule("notify_test", "失败", statusCodes = listOf(503), threshold = 1, action = KeyDisableAction.TEMPORARY, disableMinutes = 1),
        )))
        app.keyAutoDisableRepository.recordOutcome(channel.keys.single(), KeyAttemptSignal.UpstreamResponse(503), "auto-disable-test")
        val disabled = assertNotNull(app.channelRepository.find(channel.id)).keys.single()
        assertEquals(ChannelKeyState.AUTO_TEMPORARY, disabled.state)
        assertEquals(1L, app.notificationRepository.deliveries(target.id).total)
        app.keyAutoDisableRepository.recordOutcome(channel.keys.single(), KeyAttemptSignal.UpstreamResponse(503), "stale-attempt")
        assertEquals(1L, app.notificationRepository.deliveries(target.id).total)
        app.keyAutoDisableRepository.recoverExpired(assertNotNull(disabled.autoDisableUntil) + 1)
        assertEquals(2L, app.notificationRepository.deliveries(target.id).total)
        repeat(2) { app.notificationRuntime.deliverDue() }
        assertEquals(setOf("auto_temporary_disable", "expired"), sent.map { it.reasonCode }.toSet())
    }

    @Test
    fun `permanent auto disable notifies once and manual recovery is preserved`() = runBlocking {
        replaceApp()
        val channel = channel()
        val target = target(listOf(NotificationEvents.KEY_DISABLED, NotificationEvents.KEY_RECOVERED))
        app.keyAutoDisableRepository.saveSettings(KeyDisablePolicy(rules = listOf(
            KeyDisableRule("notify_auth", "凭据失败", statusCodes = listOf(401), threshold = 1, action = KeyDisableAction.PERMANENT),
        )))
        app.keyAutoDisableRepository.recordOutcome(channel.keys.single(), KeyAttemptSignal.UpstreamResponse(401), "permanent-test")
        assertEquals(ChannelKeyState.AUTO_PERMANENT, app.channelRepository.find(channel.id)?.keys?.single()?.state)
        app.keyAutoDisableRepository.recordOutcome(channel.keys.single(), KeyAttemptSignal.UpstreamResponse(401), "stale-test")
        app.keyAutoDisableRepository.recoverExpired(System.currentTimeMillis() + 86_400_000)
        assertEquals(1L, app.notificationRepository.deliveries(target.id).total)
        setState(channel, ChannelKeyState.ACTIVE)
        repeat(2) { app.notificationRuntime.deliverDue() }
        assertEquals(listOf("auto_permanent_disable", "manual_recovery"), sent.map { it.reasonCode })
    }

    @Test
    fun `pending notifications survive restart and disabled targets cancel pending delivery`() = runBlocking {
        replaceApp()
        val channel = channel()
        val target = target(listOf(NotificationEvents.KEY_DISABLED))
        setState(channel, ChannelKeyState.MANUAL_DISABLED)
        assertTrue(sent.isEmpty())
        replaceApp()
        app.notificationRuntime.deliverDue()
        assertEquals(1, sent.size)
        assertEquals("succeeded", app.notificationRepository.deliveries(target.id).items.single().status)
        setState(channel, ChannelKeyState.ACTIVE)
        setState(channel, ChannelKeyState.MANUAL_DISABLED)
        app.notificationRepository.saveTarget(target.copy(enabled = false))
        app.notificationRuntime.deliverDue()
        assertEquals(1, sent.size)
        assertEquals(setOf("succeeded", "cancelled"), app.notificationRepository.deliveries(target.id).items.map { it.status }.toSet())
    }

    @Test
    fun `rolled back key transaction cannot leave an outbox event`() = runBlocking {
        replaceApp()
        val channel = channel()
        val target = target(listOf(NotificationEvents.KEY_DISABLED))
        val repository = ChannelRepository(app.databaseFactory.database, app.credentialCipher, KeyStateObserver {
            app.notificationRepository.enqueueInTransaction(it.notificationEvent())
            error("rollback fixture")
        })
        assertFailsWith<IllegalStateException> { setState(channel, ChannelKeyState.MANUAL_DISABLED, repository) }
        assertEquals(ChannelKeyState.ACTIVE, app.channelRepository.find(channel.id)?.keys?.single()?.state)
        assertEquals(0L, app.notificationRepository.deliveries(target.id).total)
    }

    @Test
    fun `real attempts trigger once and successful request sends recovery despite delivery errors`() = runBlocking {
        replaceApp(NotificationSendResult(success = false, retryable = false, statusCode = 400, errorCode = "http_rejected"))
        val channel = channel()
        val target = target(listOf(NotificationEvents.CONSECUTIVE_FAILURES, NotificationEvents.CONSECUTIVE_FAILURES_RECOVERED))
        app.keyAutoDisableRepository.saveSettings(KeyDisablePolicy(enabled = false))
        val clientKey = createClientKey()
        fake.keyStatuses["Bearer $upstreamCredential"] = 503
        testApplication {
            application { app.install(this) }
            repeat(3) {
                app.candidateCooldown.clearChannel(channel.id)
                val response = client.post("/v1/chat/completions") {
                    header(HttpHeaders.Authorization, "Bearer $clientKey")
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"m1","messages":[]}""")
                }
                assertTrue(response.status.value >= 400)
            }
            awaitCondition("failure notification stored") { app.notificationRepository.deliveries(target.id).total == 1L }
            fake.keyStatuses["Bearer $upstreamCredential"] = 200
            app.candidateCooldown.clearChannel(channel.id)
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $clientKey")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"m1","messages":[]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            awaitCondition("recovery notification stored") { app.notificationRepository.deliveries(target.id).total == 2L }
            app.notificationRuntime.deliverDue()
            awaitCondition("notification failures recorded") { app.notificationRepository.deliveries(target.id).items.all { it.status == "failed" } }
            assertEquals(setOf(NotificationEvents.CONSECUTIVE_FAILURES, NotificationEvents.CONSECUTIVE_FAILURES_RECOVERED), sent.map { it.eventType }.toSet())
        }
    }

    @Test
    fun `health polling stores unavailable credentials once and does not mistake unknown for recovery`() = runBlocking {
        replaceApp()
        val channel = channel()
        val target = target(listOf(NotificationEvents.CHANNEL_UNHEALTHY, NotificationEvents.CHANNEL_RECOVERED))
        setState(channel, ChannelKeyState.MANUAL_DISABLED)
        app.notificationRuntime.checkHealth()
        app.notificationRuntime.checkHealth()
        assertEquals(1L, app.notificationRepository.deliveries(target.id).total)
        app.notificationRuntime.deliverDue()
        assertEquals(NotificationEvents.CHANNEL_UNHEALTHY, sent.single().eventType)
        assertEquals("no_credentials", sent.single().reasonCode)
        setState(channel, ChannelKeyState.ACTIVE)
        app.notificationRuntime.checkHealth()
        assertEquals(1L, app.notificationRepository.deliveries(target.id).total)
    }

    @Test
    fun `admin APIs require session and csrf and expose references only`() = runBlocking {
        replaceApp()
        val configured = target(listOf(NotificationEvents.KEY_DISABLED))
        val session = app.sessions.create("admin")
        testApplication {
            application { app.install(this) }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/api/notification-targets").status)
            val denied = client.put("/admin/api/notification-settings") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                contentType(ContentType.Application.Json)
                setBody("""{"enabled":true,"consecutiveFailureThreshold":2}""")
            }
            assertEquals(HttpStatusCode.Forbidden, denied.status)
            val listed = client.get("/admin/api/notification-targets") { header(HttpHeaders.Cookie, "gw_session=${session.token}") }
            assertEquals(HttpStatusCode.OK, listed.status)
            assertTrue(listed.bodyAsText().contains("PRISM_NOTIFY_TEST"))
            assertFalse(listed.bodyAsText().contains("receiver.invalid"))
            val invalid = client.put("/admin/api/notification-settings") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"enabled":true,"consecutiveFailureThreshold":0}""")
            }
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            val created = client.post("/admin/api/notification-targets") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"created target","type":"webhook","enabled":false,"events":["key.disabled"],"channelIds":[],"environmentPrefix":"PRISM_NOTIFY_TEST"}""")
            }
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            val createdJson = Json.parseToJsonElement(created.bodyAsText()).jsonObject
            val createdId = createdJson.getValue("id").jsonPrimitive.content
            val revision = createdJson.getValue("revision").jsonPrimitive.content.toLong()
            assertEquals(1L, revision)
            val updated = client.put("/admin/api/notification-targets/$createdId") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"updated target","type":"webhook","enabled":false,"events":["key.disabled"],"channelIds":[],"environmentPrefix":"PRISM_NOTIFY_TEST"}""")
            }
            assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())
            assertEquals("updated target", Json.parseToJsonElement(updated.bodyAsText()).jsonObject.getValue("name").jsonPrimitive.content)
            val removed = client.delete("/admin/api/notification-targets/$createdId") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
            }
            assertEquals(HttpStatusCode.NoContent, removed.status)
            val test = client.post("/admin/api/notification-targets/${configured.id}/test") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
            }
            assertEquals(HttpStatusCode.Accepted, test.status, test.bodyAsText())
            val eventId = Json.parseToJsonElement(test.bodyAsText()).jsonObject.getValue("eventId").jsonPrimitive.content
            awaitCondition("test notification delivered") { sent.any { it.eventId == eventId } }
            assertEquals(NotificationEvents.TEST, sent.single { it.eventId == eventId }.eventType)
        }
    }
}
