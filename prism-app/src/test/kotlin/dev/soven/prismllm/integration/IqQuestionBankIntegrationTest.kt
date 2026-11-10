package dev.soven.prismllm.integration

import dev.soven.prismllm.admin.domain.IqQuestionInput
import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.shared.domain.UpstreamRequestOverrides
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("integration")
@Tag("shard-3")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IqQuestionBankIntegrationTest : GatewayIntegrationTestBase() {
    @Test
    fun fresh_migration_seeds_the_original_question() {
        DriverManager.getConnection(IntegrationPostgres.newDatabase(), postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select prompt, expected_answer, answer_type from iq_questions").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("21", rows.getString("expected_answer"))
                    assertEquals("number", rows.getString("answer_type"))
                    assertTrue(rows.getString("prompt").contains("\n"))
                    assertFalse(rows.next())
                }
            }
        }
    }

    @Test
    fun admin_manages_questions_and_snapshot_survives_edit_and_delete() = runBlocking {
        val channel = createChannel("bank", "iq-json")
        val session = app.sessions.create("admin")
        testApplication {
            application { app.install(this) }
            val create = client.post("/admin/api/iq-tests/questions") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"title":"快照题","prompt":"计算七乘三","answerType":"number","expectedAnswer":"21.0","enabled":true}""")
            }
            assertEquals(HttpStatusCode.Created, create.status, create.bodyAsText())
            val questionId = Json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
            val run = client.post("/admin/api/iq-tests") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"channelId":"${channel.id}","model":"gpt-4o-mini","benchmark":"candy","useQuestionBank":true}""")
            }
            assertEquals(HttpStatusCode.OK, run.status, run.bodyAsText())
            val body = Json.parseToJsonElement(run.bodyAsText()).jsonObject
            assertEquals("passed", body["verdict"]!!.jsonPrimitive.content)
            assertEquals("21", body["expectedAnswerValue"]!!.jsonPrimitive.content)
            assertEquals("21", body["actualAnswer"]!!.jsonPrimitive.content)
            assertEquals(questionId, body["questionId"]!!.jsonPrimitive.content)
            val runId = body["id"]!!.jsonPrimitive.content
            val update = client.put("/admin/api/iq-tests/questions/$questionId") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"title":"已改题","prompt":"别的问题","answerType":"text","expectedAnswer":"秘密答案","enabled":false}""")
            }
            assertEquals(HttpStatusCode.OK, update.status, update.bodyAsText())
            val listing = client.get("/admin/api/iq-tests/questions?limit=1&offset=0") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
            }
            assertEquals(1, Json.parseToJsonElement(listing.bodyAsText()).jsonArray.size)
            val deleted = client.delete("/admin/api/iq-tests/questions/$questionId") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
            }
            assertEquals(HttpStatusCode.NoContent, deleted.status)
            val detail = client.get("/admin/api/iq-tests/$runId") { header(HttpHeaders.Cookie, "gw_session=${session.token}") }
            val saved = Json.parseToJsonElement(detail.bodyAsText()).jsonObject
            assertEquals("快照题", saved["questionTitle"]!!.jsonPrimitive.content)
            assertEquals("21", saved["expectedAnswerValue"]!!.jsonPrimitive.content)
            assertEquals("passed", saved["verdict"]!!.jsonPrimitive.content)
            assertTrue(fake.manxueRequests.isEmpty())
        }
    }

    @Test
    fun empty_bank_disabled_questions_and_prompt_override_are_rejected_before_calling_upstream() = runBlocking {
        val channel = createChannel("empty-bank", "iq-json")
        app.iqQuestionRepository.create(IqQuestionInput("disabled", "question", "text", "secret", false))
        val session = app.sessions.create("admin")
        testApplication {
            application { app.install(this) }
            val unauthenticated = client.get("/admin/api/iq-tests/questions")
            assertEquals(HttpStatusCode.Unauthorized, unauthenticated.status)
            val withoutCsrf = client.post("/admin/api/iq-tests/questions") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.Forbidden, withoutCsrf.status)
            for (extra in listOf("", ",\"prompt\":\"override\"")) {
                val response = client.post("/admin/api/iq-tests") {
                    header(HttpHeaders.Cookie, "gw_session=${session.token}")
                    header("X-CSRF-Token", session.session.csrf)
                    contentType(ContentType.Application.Json)
                    setBody("""{"channelId":"${channel.id}","model":"gpt-4o-mini","benchmark":"candy","useQuestionBank":true$extra}""")
                }
                assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            }
            assertTrue(fake.requests.isEmpty())
        }
    }

    @Test
    fun actual_requests_for_all_protocols_do_not_include_answers_or_injected_tools() = runBlocking {
        app.iqQuestionRepository.create(IqQuestionInput("private title", "question only", "text", "SECRET_ANSWER_SENTINEL", true))
        val injection = Json.parseToJsonElement("""{"tools":[{"type":"web_search"}],"tool_choice":"required","enable_search":true,"messages":[{"role":"user","content":"INJECTED_INPUT"}],"input":"INJECTED_INPUT","previous_response_id":"old"}""").jsonObject
        app.upstreamRequestSettingsRepository.save(UpstreamRequestOverrides(body = injection))
        val session = app.sessions.create("admin")
        testApplication {
            application { app.install(this) }
            for (protocol in GatewayProtocol.entries) {
                val channel = createChannelWithBaseUrl("isolated-${protocol.value}", "${fake.baseUrl}/ok/v1", listOf(protocol),
                    requestOverrides = UpstreamRequestOverrides(body = injection))
                val run = client.post("/admin/api/iq-tests") {
                    header(HttpHeaders.Cookie, "gw_session=${session.token}")
                    header("X-CSRF-Token", session.session.csrf)
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        put("channelId", channel.id); put("model", "gpt-4o-mini"); put("benchmark", "candy")
                        put("useQuestionBank", true); put("protocol", protocol.value)
                    }.toString())
                }
                assertEquals(HttpStatusCode.OK, run.status, run.bodyAsText())
                val sent = fake.requests.last().body
                assertTrue(sent.contains("question only"), protocol.value)
                for (forbidden in listOf("SECRET_ANSWER_SENTINEL", "private title", "INJECTED_INPUT", "web_search", "enable_search", "tool_choice", "previous_response_id")) {
                    assertFalse(sent.contains(forbidden), "${protocol.value}: $forbidden in $sent")
                }
            }
            assertTrue(fake.manxueRequests.isEmpty())
        }
    }
}
