package dev.soven.prismllm.integration

import dev.soven.prismllm.api.GatewayApplication
import dev.soven.prismllm.channel.domain.ChannelModelRecord
import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.channel.persistence.NewChannelKey
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 数据面集成测试：真实 PostgreSQL、假上游、Ktor 测试宿主。
 *
 * @author 文杰
 * @since 2026-10-21
 */

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
@Tag("shard-4")
@Tag("channel")
class AdminChannelIntegrationTest : GatewayIntegrationTestBase() {

    @Test
    fun `higher priority key wins when canonical id maps to a different upstream model name`() = runBlocking {
        app.channelRepository.create(
            name = "canonical-priority",
            baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(
                NewChannelKey(
                    name = "welfare",
                    credential = app.credentialCipher.encrypt("key-welfare"),
                    priority = 10,
                    models = listOf("deepseek-v4.1-flash"),
                ),
                NewChannelKey(
                    name = "guomo",
                    credential = app.credentialCipher.encrypt("key-guomo"),
                    priority = 100,
                    models = listOf("deepseek-flash"),
                ),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = listOf(
                ChannelModelRecord(upstreamModel = "deepseek-v4.1-flash", canonicalModelId = "deepseek-flash"),
                ChannelModelRecord(upstreamModel = "deepseek-flash"),
            ),
            priority = 1,
        )
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"deepseek-flash","messages":[]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertEquals(listOf("Bearer key-welfare"), fake.requests.map { it.authorization })

            val log = awaitRequestLog()
            assertEquals("welfare", log.channelKeyName)
            assertEquals("deepseek-v4.1-flash", log.upstreamModel)
            assertEquals(1, log.upstreamAttempts)
        }
    }

    @Test
    fun `admin rejects client-submitted auto disable states`() = runBlocking {
        val session = app.sessions.create("admin")

        testApplication {
            application { app.install(this) }
            for (autoState in listOf("AUTO_TEMPORARY", "AUTO_PERMANENT")) {
                val response = client.post("/admin/api/channels") {
                    header(HttpHeaders.Cookie, "gw_session=${session.token}")
                    header("X-CSRF-Token", session.session.csrf)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "name":"auto-state-$autoState",
                          "baseUrl":"${fake.baseUrl}/ok/v1",
                          "keys":[{"name":"primary","credential":"secret","state":"$autoState","models":["m1"]}],
                          "modelMappings":[{"upstreamModel":"m1"}],
                          "enabled":true
                        }
                        """.trimIndent(),
                    )
                }
                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertTrue(response.bodyAsText().contains("invalid_channel"))
            }
        }
    }

    @Test
    fun `models excludes canonical ids without enabled channel`() = runBlocking {
        val channel = createChannel("openai", "ok")
        exposeUnderCanonicalId("fast-chat", listOf(channel.id to "m1"))
        app.channelRepository.update(
            id = channel.id,
            name = channel.name,
            baseUrl = channel.baseUrl,
            authType = channel.authType,
            enabled = false,
        )
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.get("/v1/models") {
                header(HttpHeaders.Authorization, "Bearer $key")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(!response.bodyAsText().contains("fast-chat"))
        }
    }

    @Test
    fun `exposes and routes upstream models without aliases when enabled`() = runBlocking {
        app.close()
        app = GatewayApplication(config())
        val channel = createChannel("openai", "ok")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val models = client.get("/v1/models") {
                header(HttpHeaders.Authorization, "Bearer $key")
            }
            assertEquals(HttpStatusCode.OK, models.status)
            assertTrue(models.bodyAsText().contains("gpt-4o-mini"))

            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"gpt-4o-mini","stream":false,"messages":[]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("Hello!"))

            val log = awaitRequestLog()
            assertEquals(channel.id, log.channelId)
            assertEquals("gpt-4o-mini", log.upstreamModel)
        }
    }

    @Test
    fun `admin channel model mappings persist and allow shared canonical ids`() = runBlocking {
        val channel = createChannelWithBaseUrl(
            "mapping-api",
            "${fake.baseUrl}/ok/v1",
            modelMappings = mappingsOf("gpt-4o-mini", "deepseek-chat"),
        )
        val session = app.sessions.create("admin")

        testApplication {
            application { app.install(this) }
            val updated = client.put("/admin/api/channels/${channel.id}") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "name":"mapping-api",
                      "baseUrl":"${fake.baseUrl}/ok/v1",
                      "protocols":["chat_completions"],
                      "enabled":true,
                      "modelMappings":[{"upstreamModel":"gpt-4o-mini","canonicalModelId":"gpt-4o-mini-official","mappingSource":"manual"}]
                    }
                    """.trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.OK, updated.status)
            val updatedBody = updated.bodyAsText()
            assertTrue(updatedBody.contains("gpt-4o-mini-official"), "BODY=[$updatedBody]")
            assertTrue(updatedBody.contains("\"mappingSource\":\"manual\""), "BODY=[$updatedBody]")

            val duplicate = client.put("/admin/api/channels/${channel.id}") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "name":"mapping-api",
                      "baseUrl":"${fake.baseUrl}/ok/v1",
                      "protocols":["chat_completions"],
                      "enabled":true,
                      "modelMappings":[
                        {"upstreamModel":"gpt-4o-mini","canonicalModelId":"same-id","mappingSource":"manual"},
                        {"upstreamModel":"deepseek-chat","canonicalModelId":"same-id","mappingSource":"manual"}
                      ]
                    }
                    """.trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.OK, duplicate.status)
            val duplicateBody = duplicate.bodyAsText()
            assertTrue(duplicateBody.contains("gpt-4o-mini"), "BODY=[$duplicateBody]")
            assertTrue(duplicateBody.contains("deepseek-chat"), "BODY=[$duplicateBody]")
            val sharedCandidates = app.modelCatalog.findCandidates("same-id", GatewayProtocol.CHAT_COMPLETIONS)
            assertEquals(
                listOf("deepseek-chat", "gpt-4o-mini"),
                sharedCandidates.map { it.upstreamModel }.sorted(),
            )
            assertEquals(1, app.modelCatalog.listEnabledModels().count { it == "same-id" })

            val publicKey = createClientKey()
            val exposed = client.get("/v1/models") {
                header(HttpHeaders.Authorization, "Bearer $publicKey")
            }
            assertEquals(HttpStatusCode.OK, exposed.status)
            val exposedIds = Json.parseToJsonElement(exposed.bodyAsText()).jsonObject["data"]!!.jsonArray
                .map { it.jsonObject["id"]!!.jsonPrimitive.content }
            assertEquals(listOf("same-id"), exposedIds)

            val canonicalConflictsWithUnmapped = client.put("/admin/api/channels/${channel.id}") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "name":"mapping-api",
                      "baseUrl":"${fake.baseUrl}/ok/v1",
                      "protocols":["chat_completions"],
                      "enabled":true,
                      "modelMappings":[
                        {"upstreamModel":"Qwen-Fast","canonicalModelId":"gpt-4o-mini"},
                        {"upstreamModel":"gpt-4o-mini"}
                      ]
                    }
                    """.trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.OK, canonicalConflictsWithUnmapped.status)

            val caseDistinctUnmapped = client.put("/admin/api/channels/${channel.id}") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "name":"mapping-api",
                      "baseUrl":"${fake.baseUrl}/ok/v1",
                      "protocols":["chat_completions"],
                      "enabled":true,
                      "modelMappings":[
                        {"upstreamModel":"model-A"},
                        {"upstreamModel":"MODEL-a"}
                      ]
                    }
                    """.trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.OK, caseDistinctUnmapped.status)
        }
    }

    @Test
    fun `lists models from an upstream channel`() = runBlocking {
        val channel = createChannel("openai", "ok")
        val session = app.sessions.create("admin")

        testApplication {
            application { app.install(this) }
            val response = client.get("/admin/api/channels/${channel.id}/models") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
            }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(body.contains("gpt-4o-mini"), "BODY=[$body]")
            assertTrue(body.contains("deepseek-chat"), "BODY=[$body]")
        }
    }

    @Test
    fun `public models lists canonical and unmapped upstream ids`() = runBlocking {
        app.close()
        app = GatewayApplication(config())
        val channel = createChannel("openai", "ok")
        exposeAsModel("fast-chat", channel to "gpt-4o-mini")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.get("/v1/models") {
                header(HttpHeaders.Authorization, "Bearer $key")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val data = Json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]!!.jsonArray
            val ids = data.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            // 已映射的上游模型只以 canonical ID 对外暴露。
            assertEquals(setOf("fast-chat", "deepseek-chat", "m1", "m2"), ids.toSet())
            assertEquals(ids.sorted(), ids)
            assertTrue(data.all { it.jsonObject["owned_by"]!!.jsonPrimitive.content == "PrismLLM" })
        }
    }

    @Test
    fun `admin batch rejects more than 200 raw model entries before normalization`() = runBlocking {
        val channel = createChannel("openai", "ok")

        testApplication {
            application { app.install(this) }
            val (sessionCookie, csrf) = client.loginAdmin()
            val oversizedLists = listOf(
                List(201) { "duplicate-model" },
                List(201) { "" },
            )

            for (models in oversizedLists) {
                val serializedModels = models.joinToString(",") { model -> "\"$model\"" }
                val response = client.post("/admin/api/model-prices/batch") {
                    header(HttpHeaders.Cookie, sessionCookie)
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"channelId":"${channel.id}","upstreamModels":[$serializedModels],"inputPricePerMillion":"1","outputPricePerMillion":"2"}""",
                    )
                }

                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertTrue(response.bodyAsText().contains("invalid_model_price"))
            }
        }
    }

    @Test
    fun `admin local judge and manual verdict`() = runBlocking {
        val channel = createChannelWithBaseUrl("iq-html", "${fake.baseUrl}/iq-html/v1")

        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()

            val customPrompt = "自定义：画一只戴墨镜的鹈鹕骑自行车。"
            val run = client.post("/admin/api/iq-tests") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"channelId":"${channel.id}","model":"gpt-4o-mini","benchmark":"pelican","judgeId":"local","prompt":"$customPrompt"}""",
                )
            }
            assertEquals(HttpStatusCode.OK, run.status)
            val body = Json.parseToJsonElement(run.bodyAsText()).jsonObject
            assertEquals("review", body["verdict"]?.jsonPrimitive?.content)
            assertEquals("local", body["judgeResult"]?.jsonObject?.get("judgeId")?.jsonPrimitive?.content)
            assertEquals("review", body["judgeResult"]?.jsonObject?.get("verdict")?.jsonPrimitive?.content)
            assertEquals(customPrompt, body["prompt"]?.jsonPrimitive?.content)
            assertTrue(fake.requests.any { it.scenario == "iq-html" && it.body.contains(customPrompt) })
            val id = body["id"]?.jsonPrimitive?.content

            val manual = client.put("/admin/api/iq-tests/$id/manual-verdict") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"verdict":"passed","score":5,"note":"looks good"}""")
            }
            assertEquals(HttpStatusCode.OK, manual.status)
            val updated = Json.parseToJsonElement(manual.bodyAsText()).jsonObject
            assertEquals("passed", updated["verdict"]?.jsonPrimitive?.content)
            assertEquals("人工通过", updated["verdictLabel"]?.jsonPrimitive?.content)
            assertEquals("passed", updated["manualVerdict"]?.jsonPrimitive?.content)
            assertEquals("5", updated["manualScore"]?.jsonPrimitive?.content)

            val history = client.get("/admin/api/iq-tests") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            val item = Json.parseToJsonElement(history.bodyAsText()).jsonArray
                .map { it.jsonObject }
                .first { it["id"]?.jsonPrimitive?.content == id }
            assertEquals("passed", item["verdict"]?.jsonPrimitive?.content)
            assertEquals("passed", item["manualVerdict"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `admin tests a channel model without reasoning effort`() = runBlocking {
        val channel = createChannel("model-test", "ok")

        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()
            val response = client.post("/admin/api/channels/${channel.id}/model-test") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"model":"gpt-4o-mini","protocol":"chat_completions","prompt":"custom prompt","reasoningEffort":"high"}""",
                )
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(true, json["success"]?.jsonPrimitive?.content?.toBoolean())
            assertEquals("Hello!", json["responseText"]?.jsonPrimitive?.content)
            assertEquals("10", json["usage"]?.jsonObject?.get("inputTokens")?.jsonPrimitive?.content)
            assertTrue("reasoningEffort" in json)
            assertEquals(JsonNull, json["reasoningEffort"])
            val upstream = fake.requests.last().body
            assertFalse(upstream.contains("reasoning_effort"), upstream)
            assertTrue(upstream.contains("\"content\":\"custom prompt\""), upstream)
            assertTrue(upstream.contains("\"max_tokens\":128"), upstream)
        }
    }

    @Test
    fun `admin login requires csrf and created key can call models`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()

            val channelResponse = client.post("/admin/api/channels") {
                header(HttpHeaders.Cookie, sessionCookie)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"openai","baseUrl":"${fake.baseUrl}/ok/v1","credential":"upstream-secret"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, channelResponse.status)

            val createdChannel = client.post("/admin/api/channels") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"openai","baseUrl":"${fake.baseUrl}/ok/v1","credential":"upstream-secret","models":["gpt-4o-mini"]}""")
            }
            assertEquals(HttpStatusCode.Created, createdChannel.status)
            val channelId = Json.parseToJsonElement(createdChannel.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

            // 渠道授权的上游模型直接对外暴露，不再需要别名层。
            assertTrue(app.modelCatalog.listEnabledModels().contains("gpt-4o-mini"))

            val createdKey = client.post("/admin/api/client-keys") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"test-key"}""")
            }
            assertEquals(HttpStatusCode.Created, createdKey.status)
            val key = Json.parseToJsonElement(createdKey.bodyAsText()).jsonObject["key"]!!.jsonPrimitive.content

            val models = client.get("/v1/models") {
                header(HttpHeaders.Authorization, "Bearer $key")
            }
            assertEquals(HttpStatusCode.OK, models.status)
            assertTrue(models.bodyAsText().contains("gpt-4o-mini"))
        }
    }

    @Test
    fun `admin discovers upstream models before saving a channel`() = runBlocking {
        val channel = createChannelWithBaseUrl("discover", "${fake.baseUrl}/ok/v1").let { created ->
            app.channelRepository.update(
                id = created.id,
                name = created.name,
                baseUrl = created.baseUrl,
                authType = created.authType,
                protocols = created.protocols,
                enabled = true,
                modelMappings = mappingsOf("gpt-4o-mini"),
            )!!
        }
        val session = app.sessions.create("admin")

        testApplication {
            application { app.install(this) }
            val beforeSave = client.post("/admin/api/channels/model-discovery") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"baseUrl":"${fake.baseUrl}/ok/v1","credential":"upstream-secret"}""")
            }
            assertEquals(HttpStatusCode.OK, beforeSave.status)
            val beforeSaveBody = beforeSave.bodyAsText()
            assertTrue(beforeSaveBody.contains("gpt-4o-mini"), "BODY=[$beforeSaveBody]")
            assertTrue(beforeSaveBody.contains("\"authorized\":false"), "BODY=[$beforeSaveBody]")

            val withSavedCredential = client.post("/admin/api/channels/model-discovery") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"baseUrl":"${channel.baseUrl}","channelId":"${channel.id}"}""")
            }
            assertEquals(HttpStatusCode.OK, withSavedCredential.status)
            val savedBody = withSavedCredential.bodyAsText()
            val savedModels = Json.parseToJsonElement(savedBody).jsonObject["data"]!!.jsonArray
                .associate { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject }
            assertEquals("true", savedModels.getValue("gpt-4o-mini")["authorized"]!!.jsonPrimitive.content)
            assertEquals("false", savedModels.getValue("deepseek-chat")["authorized"]!!.jsonPrimitive.content)

            val replacement = client.post("/admin/api/channels/model-discovery") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"baseUrl":"${channel.baseUrl}","channelId":"${channel.id}","credential":"replacement-secret"}""")
            }
            assertEquals(HttpStatusCode.OK, replacement.status)
            val replacementModels = Json.parseToJsonElement(replacement.bodyAsText()).jsonObject["data"]!!.jsonArray
            assertTrue(replacementModels.all { it.jsonObject["authorized"]!!.jsonPrimitive.content == "false" })
            assertEquals("Bearer replacement-secret", fake.modelRequests.last().authorization)

            val withExplicitKey = client.post("/admin/api/channels/model-discovery") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"baseUrl":"${channel.baseUrl}","channelId":"${channel.id}","keyId":"${channel.keys.single().id}"}""")
            }
            assertEquals(HttpStatusCode.OK, withExplicitKey.status)
            assertTrue(withExplicitKey.bodyAsText().contains("gpt-4o-mini"))

            val mixedCredential = client.post("/admin/api/channels/model-discovery") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"baseUrl":"${channel.baseUrl}","channelId":"${channel.id}","keyId":"${channel.keys.single().id}","credential":"upstream-secret"}""",
                )
            }
            assertEquals(HttpStatusCode.BadRequest, mixedCredential.status)
            assertTrue(mixedCredential.bodyAsText().contains("keyId 与 credential 不能同时提交"))

            val changedBaseUrl = client.post("/admin/api/channels/model-discovery") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"baseUrl":"${fake.baseUrl}/500/v1","channelId":"${channel.id}"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, changedBaseUrl.status)
            assertTrue(changedBaseUrl.bodyAsText().contains("请重新输入上游凭据"))
        }
    }

    @Test
    fun `channel update without models preserves authorization`() = runBlocking {
        val channel = createChannelWithBaseUrl("preserve", "${fake.baseUrl}/ok/v1")
        val session = app.sessions.create("admin")

        testApplication {
            application { app.install(this) }
            val response = client.put("/admin/api/channels/${channel.id}") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"preserve-renamed","baseUrl":"${fake.baseUrl}/ok/v1","protocols":["chat_completions"],"enabled":true}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val updatedModels = Json.parseToJsonElement(response.bodyAsText()).jsonObject["models"]!!.jsonArray
                .map { it.jsonPrimitive.content }
            assertEquals(channel.models.sorted(), updatedModels.sorted())
        }
    }
}
