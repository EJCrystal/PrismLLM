package dev.soven.llmgateway.integration

import dev.soven.llmgateway.config.OAuthConfig
import dev.soven.llmgateway.channel.domain.ChannelRecord
import dev.soven.llmgateway.shared.domain.GatewayProtocol
import dev.soven.llmgateway.domain.OAuthAccountPolicy
import dev.soven.llmgateway.shared.domain.OAuthProvider
import dev.soven.llmgateway.oauth.OAuthAccountDecision
import dev.soven.llmgateway.oauth.OAuthAccountDenyReason
import dev.soven.llmgateway.channel.persistence.NewChannelKey
import dev.soven.llmgateway.repo.NewOAuthAccount
import dev.soven.llmgateway.resilience.FailureKind
import io.ktor.client.request.header
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 数据面集成测试：真实 PostgreSQL、假上游、Ktor 测试宿主。
 *
 * @author 文杰
 * @since 2026-10-21
 */

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
@Tag("shard-2")
class SubscriptionOAuthIntegrationTest : GatewayIntegrationTestBase() {

    @Test
    fun `subscription account window skips the candidate without calling upstream`() = runBlocking {
        val channel = app.channelRepository.create(
            name = "oauth-window",
            baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(
                NewChannelKey(
                    name = "unused-static-key",
                    credential = app.credentialCipher.encrypt("unused-static-secret"),
                    models = emptyList(),
                ),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = mappingsOf("m1"),
        )
        val account = createOAuthAccount(channel, "window@example.com")
        app.oauthAccountRepository.saveRefreshed(
            accountId = account.id,
            accessToken = app.credentialCipher.encrypt("oauth-access-token"),
            expiresAt = System.currentTimeMillis() + 3_600_000,
            tokenType = "Bearer",
            scope = null,
            rotatedRefreshToken = null,
            expectedRefreshCiphertext = account.refreshTokenCiphertext,
        )
        app.oauthAccountRepository.updatePolicy(
            account.id,
            OAuthAccountPolicy(windowLimit = 1, windowSeconds = 3_600, maxConcurrentRequests = 1),
        )
        exposeAsModel("oauth-window-model", channel to "m1")
        val clientKey = createClientKey()

        testApplication {
            application { app.install(this) }
            suspend fun send() = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $clientKey")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"oauth-window-model","messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, send().status)
            val denied = send()
            assertEquals(HttpStatusCode.TooManyRequests, denied.status)
            assertTrue(denied.bodyAsText().contains("subscription_account_rate_limited"))
            assertTrue(denied.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.let { it >= 1 } == true)
            awaitRequestLogCount(2)
        }

        assertEquals(1, fake.requests.size)
        assertEquals("Bearer oauth-access-token", fake.requests.single().authorization)
    }

    @Test
    fun `subscription account requiring reauthorization never sends the refresh token upstream`() = runBlocking {
        val channel = app.channelRepository.create(
            name = "oauth-reauth",
            baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(
                NewChannelKey(
                    name = "unused-static-key",
                    credential = app.credentialCipher.encrypt("unused-static-secret"),
                    models = emptyList(),
                ),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = mappingsOf("m1"),
        )
        val account = createOAuthAccount(channel, "reauth@example.com")
        app.oauthAccountRepository.recordFailure(account.id, "invalid_grant", terminal = true)
        exposeAsModel("oauth-reauth-model", channel to "m1")
        val clientKey = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $clientKey")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"oauth-reauth-model","messages":[]}""")
            }

            assertEquals(HttpStatusCode.BadGateway, response.status)
            assertTrue(response.bodyAsText().contains("subscription_account_unavailable"))
            awaitRequestLogCount(1)
        }

        assertEquals(0, fake.requests.size)
    }

    @Test
    fun `admin model listing resolves the subscription access token`() = runBlocking {
        val channel = createChannel("openai", "ok")
        val account = createOAuthAccount(channel, "models@example.com")
        app.oauthAccountRepository.saveRefreshed(
            accountId = account.id,
            accessToken = app.credentialCipher.encrypt("oauth-models-token"),
            expiresAt = System.currentTimeMillis() + 3_600_000,
            tokenType = "Bearer",
            scope = null,
            rotatedRefreshToken = null,
            expectedRefreshCiphertext = account.refreshTokenCiphertext,
        )
        val session = app.sessions.create("admin")

        testApplication {
            application { app.install(this) }
            val response = client.get("/admin/api/channels/${channel.id}/models?keyId=${account.id}") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
            }

            assertEquals(HttpStatusCode.OK, response.status)
        }

        assertEquals("Bearer oauth-models-token", fake.modelRequests.single().authorization)
    }

    @Test
    fun `admin oauth import binds the account and lists it on the dashboard`() = runBlocking {
        val channel = createChannel("openai", "ok")
        val expiresAt = System.currentTimeMillis() + 3_600_000

        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            val response = client.post("/admin/api/oauth-accounts") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"provider":"chatgpt","refreshToken":"refresh-token-value","accountEmail":"free@example.com",""" +
                        """"accountPlan":"free","proxyUrl":"http://127.0.0.1:8080","accessToken":"access-token-value",""" +
                        """"accessTokenExpiresAt":$expiresAt,"channelIds":["${channel.id}"]}""",
                )
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertFalse(body.contains("refresh-token-value"))
            assertFalse(body.contains("access-token-value"))
            val account = Json.parseToJsonElement(body).jsonObject
            val accountId = account["id"]!!.jsonPrimitive.content
            assertTrue(accountId.isNotBlank())
            // 订阅账号只服务官方端点：请求里的 channelIds 被忽略，改挂系统置备渠道。
            val providerChannelId = assertNotNull(app.channelRepository.findProviderChannel(OAuthProvider.CHATGPT)).id
            assertEquals(providerChannelId, account["channelIds"]!!.jsonArray.single().jsonPrimitive.content)
            assertTrue(account["channelIds"]!!.jsonArray.none { it.jsonPrimitive.content == channel.id })
            assertEquals("chatgpt", account["provider"]!!.jsonPrimitive.content)
            assertEquals("ACTIVE", account["authState"]!!.jsonPrimitive.content)
            assertEquals("free@example.com", account["accountEmail"]!!.jsonPrimitive.content)
            assertEquals(expiresAt, account["accessTokenExpiresAt"]!!.jsonPrimitive.long)
            assertEquals("http://127.0.0.1:8080", account["proxyUrl"]!!.jsonPrimitive.content)
            val policy = account["policy"]!!.jsonObject
            assertEquals(10_800, policy["windowSeconds"]!!.jsonPrimitive.int)
            assertEquals(1, policy["maxConcurrentRequests"]!!.jsonPrimitive.int)
            assertEquals(0, account["runtime"]!!.jsonObject["windowUsed"]!!.jsonPrimitive.int)

            val listing = client.get("/admin/api/oauth-accounts") {
                header(HttpHeaders.Cookie, cookie)
            }
            assertEquals(HttpStatusCode.OK, listing.status)
            assertEquals("no-store", listing.headers["Cache-Control"])
            assertFalse(listing.bodyAsText().contains("refresh-token-value"))
            val entries = Json.parseToJsonElement(listing.bodyAsText()).jsonArray
            assertTrue(entries.any { it.jsonObject["id"]!!.jsonPrimitive.content == accountId })

            val auditResponse = client.get("/admin/api/audit-logs?limit=20") {
                header(HttpHeaders.Cookie, cookie)
            }
            assertEquals(HttpStatusCode.OK, auditResponse.status)
            val importAudit = Json.parseToJsonElement(auditResponse.bodyAsText()).jsonArray
                .map { it.jsonObject }
                .single { it["action"]!!.jsonPrimitive.content == "oauth_account.import" }
            assertEquals("chatgpt", importAudit["details"]!!.jsonObject["provider"]!!.jsonPrimitive.content)
            assertEquals(
                "http://127.0.0.1:8080",
                importAudit["details"]!!.jsonObject["proxyUrl"]!!.jsonPrimitive.content,
            )

            val providers = client.get("/admin/api/oauth-providers") {
                header(HttpHeaders.Cookie, cookie)
            }
            assertEquals(HttpStatusCode.OK, providers.status)
            val byProvider = Json.parseToJsonElement(providers.bodyAsText()).jsonArray
                .associate { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["configured"]!!.jsonPrimitive.boolean }
            // Antigravity 与 ChatGPT 一样内置公开客户端参数，不需要部署方预先配置。
            assertEquals(mapOf("chatgpt" to true, "antigravity" to true), byProvider)
            val managed = Json.parseToJsonElement(providers.bodyAsText()).jsonArray
                .associate {
                    it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["managedChannel"]!!.jsonPrimitive.boolean
                }
            // Antigravity 走 Code Assist 信封协议，账号同样挂到系统置备渠道。
            assertEquals(mapOf("chatgpt" to true, "antigravity" to true), managed)
            val loginSupported = Json.parseToJsonElement(providers.bodyAsText()).jsonArray
                .associate {
                    it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["loginSupported"]!!.jsonPrimitive.boolean
                }
            // 两个提供方都内置了授权地址与回调地址，可以直接发起浏览器登录。
            assertEquals(mapOf("chatgpt" to true, "antigravity" to true), loginSupported)

            val stored = assertNotNull(app.oauthAccountRepository.find(accountId))
            assertNotEquals("refresh-token-value", stored.refreshTokenCiphertext)
            assertEquals(
                "refresh-token-value",
                app.credentialCipher.decrypt(stored.refreshTokenCiphertext, stored.refreshTokenNonce),
            )
            assertEquals(10_800, stored.policy.windowSeconds)
            assertEquals("free@example.com", stored.accountEmail)
        }
    }

    @Test
    fun `admin oauth import accepts antigravity and rejects invalid input`() = runBlocking {
        val channel = createChannel("openai", "ok")

        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            suspend fun import(body: String) = client.post("/admin/api/oauth-accounts") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            // Antigravity 内置公开客户端参数，未做任何部署配置也能导入账号。
            val antigravity = import("""{"provider":"antigravity","refreshToken":"rt"}""")
            assertEquals(HttpStatusCode.OK, antigravity.status)
            assertEquals(
                "antigravity",
                Json.parseToJsonElement(antigravity.bodyAsText()).jsonObject["provider"]!!.jsonPrimitive.content,
            )

            val unknownProvider = import("""{"provider":"gemini","refreshToken":"rt"}""")
            assertEquals(HttpStatusCode.BadRequest, unknownProvider.status)
            assertTrue(unknownProvider.bodyAsText().contains("invalid_oauth_provider"))

            val blankToken = import("""{"provider":"chatgpt","refreshToken":"   "}""")
            assertEquals(HttpStatusCode.BadRequest, blankToken.status)
            assertTrue(blankToken.bodyAsText().contains("invalid_oauth_refresh_token"))

            val proxy = import(
                """{"provider":"chatgpt","refreshToken":"rt","proxyUrl":"socks5://127.0.0.1:1080"}""",
            )
            assertEquals(HttpStatusCode.BadRequest, proxy.status)
            assertTrue(proxy.bodyAsText().contains("invalid_oauth_proxy"))

            // 远程代理主机默认不放行，避免管理面输入被用来访问任意内网主机。
            val remoteProxy = import(
                """{"provider":"chatgpt","refreshToken":"rt","proxyUrl":"http://proxy.example.com:8080"}""",
            )
            assertEquals(HttpStatusCode.BadRequest, remoteProxy.status)
            assertTrue(remoteProxy.bodyAsText().contains("OAUTH_PROXY_ALLOWED_HOSTS"))

            val missingExpiry = import("""{"provider":"chatgpt","refreshToken":"rt","accessToken":"at"}""")
            assertEquals(HttpStatusCode.BadRequest, missingExpiry.status)
            assertTrue(missingExpiry.bodyAsText().contains("invalid_oauth_access_token"))

            val expiredAccess = import(
                """{"provider":"chatgpt","refreshToken":"rt","accessToken":"at","accessTokenExpiresAt":${System.currentTimeMillis() - 60_000}}""",
            )
            assertEquals(HttpStatusCode.BadRequest, expiredAccess.status)
            assertTrue(expiredAccess.bodyAsText().contains("invalid_oauth_access_token"))

            val missingChannel = import(
                """{"provider":"chatgpt","refreshToken":"rt","channelIds":["missing"]}""",
            )
            assertEquals(HttpStatusCode.BadRequest, missingChannel.status)
            assertTrue(missingChannel.bodyAsText().contains("invalid_oauth_channels"))

            assertEquals(
                0,
                scalarInt("select count(*) from oauth_account_channels where channel_id = ?", channel.id),
            )
        }
    }

    @Test
    fun `admin oauth provider models rejects invalid body before provisioning`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            val before = scalarInt("select count(*) from channels where kind = 'provider'")
            val response = client.put("/admin/api/oauth-providers/chatgpt/models") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"models":123}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                before,
                scalarInt("select count(*) from channels where kind = 'provider'"),
                "请求体非法时不应置备系统渠道",
            )
        }
    }

    @Test
    fun `admin oauth import rejects the same account email on another key`() = runBlocking {
        val first = createChannel("openai-a", "ok")
        val second = createChannel("openai-b", "ok")

        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            suspend fun import(channel: ChannelRecord, refreshToken: String) =
                client.post("/admin/api/oauth-accounts") {
                    header(HttpHeaders.Cookie, cookie)
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"provider":"chatgpt","refreshToken":"$refreshToken","accountEmail":"same@example.com","channelIds":["${channel.id}"]}""",
                    )
                }

            assertEquals(HttpStatusCode.OK, import(first, "rt-a").status)
            val duplicated = import(second, "rt-b")
            assertEquals(HttpStatusCode.Conflict, duplicated.status)
            assertTrue(duplicated.bodyAsText().contains("oauth_account_email_exists"))

            // 冲突时整个事务回滚，不会留下第二个账号或渠道关联。
            assertEquals(1, scalarInt("select count(*) from oauth_accounts where account_email = 'same@example.com'"))
            assertEquals(
                1,
                scalarInt(
                    "select count(*) from oauth_account_channels c join oauth_accounts a on a.id = c.account_id " +
                        "where a.account_email = 'same@example.com'",
                ),
            )
        }
    }

    @Test
    fun `admin oauth policy update validates and persists limits`() = runBlocking {
        val channel = createChannel("openai", "ok")
        val account = createOAuthAccount(channel, "policy@example.com")

        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            suspend fun update(keyId: String, body: String) = client.put("/admin/api/oauth-accounts/$keyId/policy") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            val ok = update(account.id, """{"windowLimit":10,"windowSeconds":3600,"maxConcurrentRequests":2}""")
            assertEquals(HttpStatusCode.OK, ok.status)
            val policy = Json.parseToJsonElement(ok.bodyAsText()).jsonObject["policy"]!!.jsonObject
            assertEquals(10, policy["windowLimit"]!!.jsonPrimitive.int)
            assertEquals(3_600, policy["windowSeconds"]!!.jsonPrimitive.int)
            assertEquals(2, policy["maxConcurrentRequests"]!!.jsonPrimitive.int)

            val invalid = update(account.id, """{"windowLimit":0}""")
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertTrue(invalid.bodyAsText().contains("invalid_oauth_policy"))

            val missing = update("missing", """{"windowLimit":5}""")
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertTrue(missing.bodyAsText().contains("oauth_account_not_found"))

            val stored = assertNotNull(app.oauthAccountRepository.find(account.id)).policy
            assertEquals(10, stored.windowLimit)
            assertEquals(3_600, stored.windowSeconds)
            assertEquals(2, stored.maxConcurrentRequests)
        }
    }

    @Test
    fun `admin oauth cooldown clear releases account and key cooldown`() = runBlocking {
        val channel = createChannel("openai", "ok")
        val account = createOAuthAccount(channel, "cooldown@example.com")
        app.oauthAccountScheduler.recordRateLimited(account, 600_000)
        val permit = assertNotNull(app.candidateCooldown.forceAcquire(channel.id, account.id, "m1"))
        app.candidateCooldown.recordFailure(permit, FailureKind.AUTH_REJECTED)
        assertNotNull(app.oauthAccountScheduler.runtimeState(account.id, account.policy)?.cooldownUntil)
        assertNotNull(app.candidateCooldown.cooldownState(channel.id, account.id, "m1"))

        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            val response = client.post("/admin/api/oauth-accounts/${account.id}/cooldown/clear") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
            }
            assertEquals(HttpStatusCode.NoContent, response.status)

            val missing = client.post("/admin/api/oauth-accounts/missing/cooldown/clear") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
            }
            assertEquals(HttpStatusCode.NotFound, missing.status)

            assertNull(app.oauthAccountScheduler.runtimeState(account.id, account.policy)!!.cooldownUntil)
            assertNull(app.candidateCooldown.cooldownState(channel.id, account.id, "m1"))
        }
    }

    @Test
    fun `admin oauth manual refresh reports missing accounts and refresh failures`() = runBlocking {
        val channel = createChannel("openai", "ok")

        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            suspend fun refresh(keyId: String) = client.post("/admin/api/oauth-accounts/$keyId/refresh") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
            }

            val notOAuth = refresh("missing")
            assertEquals(HttpStatusCode.NotFound, notOAuth.status)
            assertTrue(notOAuth.bodyAsText().contains("oauth_account_not_found"))

            // 代理指向本机未监听端口，刷新请求快速失败，避免测试访问真实 OAuth 端点。
            val account = app.oauthAccountRepository.create(
                NewOAuthAccount(
                    name = "refresh@example.com",
                    provider = OAuthProvider.CHATGPT,
                    accountEmail = "refresh@example.com",
                    refreshToken = app.credentialCipher.encrypt("oauth-refresh-token"),
                    proxyUrl = "http://127.0.0.1:1",
                    channelIds = listOf(channel.id),
                ),
            )
            val failed = refresh(account.id)
            assertEquals(HttpStatusCode.BadGateway, failed.status)
            assertTrue(failed.bodyAsText().contains("oauth_refresh_failed"))
            assertEquals("network_error", app.oauthAccountRepository.find(account.id)!!.lastRefreshError)
        }
    }

    @Test
    fun `admin oauth quota refresh reads upstream usage and persists the snapshot`() = runBlocking {
        val channel = createChannel("openai", "ok")
        val account = createOAuthAccount(channel, "quota@example.com")
        app.oauthAccountRepository.updateChatgptAccountId(account.id, "acc-quota")
        app.oauthAccountRepository.saveRefreshed(
            account.id,
            accessToken = app.credentialCipher.encrypt("quota-access-token"),
            expiresAt = System.currentTimeMillis() + 3_600_000,
            tokenType = "Bearer",
            scope = null,
            rotatedRefreshToken = null,
            expectedRefreshCiphertext = account.refreshTokenCiphertext,
        )

        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            suspend fun refreshQuota(keyId: String) = client.post("/admin/api/oauth-accounts/$keyId/quota/refresh") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
            }

            val missing = refreshQuota("missing")
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertTrue(missing.bodyAsText().contains("oauth_account_not_found"))

            val response = refreshQuota(account.id)
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("plus", body["accountPlan"]!!.jsonPrimitive.content)
            val quota = body["quota"]!!.jsonObject
            assertEquals(100.0, quota["primary"]!!.jsonObject["usedPercent"]!!.jsonPrimitive.double)
            assertEquals(18_000, quota["primary"]!!.jsonObject["windowSeconds"]!!.jsonPrimitive.int)
            assertEquals(42.5, quota["secondary"]!!.jsonObject["usedPercent"]!!.jsonPrimitive.double)
            assertFalse(quota["allowed"]!!.jsonPrimitive.boolean)
            assertEquals(1, quota["resetCredits"]!!.jsonPrimitive.int)
            // 上游只给了 reset_after_seconds，接口返回换算后的绝对恢复时间。
            val blockedUntil = assertNotNull(body["runtime"]!!.jsonObject["quotaBlockedUntil"]?.jsonPrimitive?.long)
            assertTrue(blockedUntil > System.currentTimeMillis())

            val upstreamRequest = fake.quotaRequests.single()
            assertEquals("Bearer quota-access-token", upstreamRequest.authorization)
            assertEquals("acc-quota", upstreamRequest.requestHeaders["chatgpt-account-id"]?.single())
            assertEquals("codex_cli_rs", upstreamRequest.requestHeaders["originator"]?.single())

            val auditResponse = client.get("/admin/api/audit-logs?limit=20") {
                header(HttpHeaders.Cookie, cookie)
            }
            assertEquals(HttpStatusCode.OK, auditResponse.status)
            val quotaAudits = Json.parseToJsonElement(auditResponse.bodyAsText()).jsonArray
                .map { it.jsonObject }
                .filter { it["action"]!!.jsonPrimitive.content == "oauth_account.quota.refresh" }
            // 成功与 404 失败各一条，失败记录保留错误码便于追溯。
            assertEquals(2, quotaAudits.size)
            val successAudit = quotaAudits.first {
                it["details"]!!.jsonObject["result"]!!.jsonPrimitive.content == "success"
            }
            assertEquals(account.id, successAudit["details"]!!.jsonObject["accountId"]!!.jsonPrimitive.content)

            val stored = assertNotNull(app.oauthAccountRepository.find(account.id))
            assertEquals(100.0, assertNotNull(stored.quota).primary!!.usedPercent)
            assertEquals("acc-quota", stored.chatgptAccountId)
            // 额度用尽立即参与准入拦截，不必等下一次 429。
            val decision = app.oauthAccountScheduler.acquire(account)
            assertIs<OAuthAccountDecision.Denied>(decision)
            assertEquals(OAuthAccountDenyReason.QUOTA, decision.reason)

            // 上游读取失败时保留上一次成功读数，只记录错误码。
            fake.quotaUsageStatus = 503
            app.oauthQuotaService.refreshNow(account.id)
            val degraded = assertNotNull(app.oauthAccountRepository.find(account.id))
            assertEquals("http_503", assertNotNull(degraded.quota).error)
            assertEquals(100.0, assertNotNull(degraded.quota!!.primary).usedPercent)
        }
    }

    @Test
    fun `admin oauth interactive login exchanges the callback and binds the account`() = runBlocking {
        val channel = createChannel("openai", "ok")

        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            suspend fun start(body: String) = client.post("/admin/api/oauth-login/start") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val started = start(
                """{"provider":"chatgpt","name":"login account","channelIds":["${channel.id}"]}""",
            )
            assertEquals(HttpStatusCode.OK, started.status)
            assertEquals("no-store", started.headers["Cache-Control"])
            val startedBody = Json.parseToJsonElement(started.bodyAsText()).jsonObject
            val loginId = startedBody["loginId"]!!.jsonPrimitive.content
            val authorizeUrl = startedBody["authorizeUrl"]!!.jsonPrimitive.content
            assertTrue(authorizeUrl.startsWith("https://auth.openai.com/oauth/authorize?"))
            assertTrue(authorizeUrl.contains("code_challenge_method=S256"))
            assertEquals("http://localhost:1455/auth/callback", startedBody["redirectUri"]!!.jsonPrimitive.content)
            val state = authorizeUrl.substringAfter("state=").substringBefore("&")

            suspend fun complete(callback: String, id: String = loginId) = client.post("/admin/api/oauth-login/$id/complete") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"callbackUrl":"$callback"}""")
            }

            // state 不匹配时拒绝，且不消耗会话、不调用 token 端点。
            val wrongState = complete("http://localhost:1455/auth/callback?code=auth-code&state=wrong")
            assertEquals(HttpStatusCode.BadRequest, wrongState.status)
            assertTrue(wrongState.bodyAsText().contains("oauth_login_invalid_callback"))
            assertEquals(0, fake.tokenRequests.size)

            val completed = complete("http://localhost:1455/auth/callback?code=auth-code&state=$state")
            assertEquals(HttpStatusCode.OK, completed.status)
            val account = Json.parseToJsonElement(completed.bodyAsText()).jsonObject
            val accountId = account["id"]!!.jsonPrimitive.content
            assertEquals("login@example.com", account["accountEmail"]!!.jsonPrimitive.content)
            assertEquals("plus", account["accountPlan"]!!.jsonPrimitive.content)
            assertEquals("acc-login", account["chatgptAccountId"]!!.jsonPrimitive.content)
            assertEquals("ACTIVE", account["authState"]!!.jsonPrimitive.content)

            val tokenRequest = fake.tokenRequests.single()
            assertTrue(tokenRequest.body.contains("grant_type=authorization_code"))
            assertTrue(tokenRequest.body.contains("code=auth-code"))
            assertTrue(tokenRequest.body.contains("code_verifier="))
            assertFalse(tokenRequest.body.contains("state="))
            assertEquals("codex_cli_rs", tokenRequest.requestHeaders["originator"]?.single())

            // 会话单次使用：重复提交同一个回调只会得到过期，不会再次换取 token。
            val replay = complete("http://localhost:1455/auth/callback?code=auth-code&state=$state")
            assertEquals(HttpStatusCode.BadRequest, replay.status)
            assertTrue(replay.bodyAsText().contains("oauth_login_expired"))
            assertEquals(1, fake.tokenRequests.size)

            // refresh token 与 access token 都加密落库，响应不回显。
            val storedAccount = assertNotNull(app.oauthAccountRepository.find(accountId))
            assertEquals(
                "login-refresh-token",
                app.credentialCipher.decrypt(storedAccount.refreshTokenCiphertext, storedAccount.refreshTokenNonce),
            )
            assertEquals(
                "login-access-token",
                app.credentialCipher.decrypt(
                    assertNotNull(storedAccount.accessTokenCiphertext),
                    assertNotNull(storedAccount.accessTokenNonce),
                ),
            )

            val auditResponse = client.get("/admin/api/audit-logs?limit=20") {
                header(HttpHeaders.Cookie, cookie)
            }
            val loginAudits = Json.parseToJsonElement(auditResponse.bodyAsText()).jsonArray
                .map { it.jsonObject }
                .filter { it["action"]!!.jsonPrimitive.content == "oauth_account.login" }
            // state 不匹配、成功、会话重放各一条。
            assertEquals(3, loginAudits.size)
            assertTrue(
                loginAudits.any {
                    it["details"]?.jsonObject?.get("result")?.jsonPrimitive?.content == "success"
                },
            )
        }
    }

    @Test
    fun `admin oauth interactive login supports antigravity through google oauth`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            val started = client.post("/admin/api/oauth-login/start") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"provider":"antigravity"}""")
            }
            assertEquals(HttpStatusCode.OK, started.status)
            val startedBody = Json.parseToJsonElement(started.bodyAsText()).jsonObject
            val loginId = startedBody["loginId"]!!.jsonPrimitive.content
            val authorizeUrl = startedBody["authorizeUrl"]!!.jsonPrimitive.content
            assertTrue(authorizeUrl.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
            assertTrue(authorizeUrl.contains("client_id=${OAuthConfig.DEFAULT_ANTIGRAVITY_CLIENT_ID}"))
            assertTrue(authorizeUrl.contains("redirect_uri=http%3A%2F%2Flocalhost%3A8085%2Fcallback"))
            assertTrue(authorizeUrl.contains("access_type=offline"))
            assertTrue(authorizeUrl.contains("prompt=consent"))
            assertTrue(authorizeUrl.contains("code_challenge_method=S256"))
            assertTrue(authorizeUrl.contains("scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fcloud-platform"))
            val state = authorizeUrl.substringAfter("state=").substringBefore("&")

            val completed = client.post("/admin/api/oauth-login/$loginId/complete") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"callbackUrl":"http://localhost:8085/callback?code=google-code&state=$state"}""")
            }
            assertEquals(HttpStatusCode.OK, completed.status)
            val account = Json.parseToJsonElement(completed.bodyAsText()).jsonObject
            assertEquals("antigravity", account["provider"]!!.jsonPrimitive.content)
            assertEquals("ACTIVE", account["authState"]!!.jsonPrimitive.content)

            // Google 的授权码交换同样带 client_secret，且不带 Codex 的身份头。
            val tokenRequest = fake.tokenRequests.single()
            assertTrue(tokenRequest.body.contains("grant_type=authorization_code"))
            assertTrue(tokenRequest.body.contains("code=google-code"))
            assertTrue(tokenRequest.body.contains("client_id=${OAuthConfig.DEFAULT_ANTIGRAVITY_CLIENT_ID}"))
            // 断言消息不要带上请求体：请求体包含真实的 client_secret。
            assertTrue(
                tokenRequest.body.split("&").contains("client_secret=${OAuthConfig.DEFAULT_ANTIGRAVITY_CLIENT_SECRET}"),
                "token request 缺少 client_secret",
            )
            assertNull(tokenRequest.requestHeaders["originator"])
        }
    }

    @Test
    fun `admin oauth interactive login rejects an account deleted during login`() = runBlocking {
        val channel = createChannel("openai", "ok")
        val account = createOAuthAccount(channel, "stale@example.com")

        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            val started = client.post("/admin/api/oauth-login/start") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"provider":"chatgpt","accountId":"${account.id}"}""")
            }
            assertEquals(HttpStatusCode.OK, started.status)
            val startedBody = Json.parseToJsonElement(started.bodyAsText()).jsonObject
            val loginId = startedBody["loginId"]!!.jsonPrimitive.content
            val state = startedBody["authorizeUrl"]!!.jsonPrimitive.content
                .substringAfter("state=").substringBefore("&")

            // 登录期间目标被删除：完成回调必须拒绝绑定，且不消耗授权码。
            app.oauthAccountRepository.delete(account.id)
            val completed = client.post("/admin/api/oauth-login/$loginId/complete") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"callbackUrl":"http://localhost:1455/auth/callback?code=auth-code&state=$state"}""")
            }
            assertEquals(HttpStatusCode.Conflict, completed.status)
            assertTrue(completed.bodyAsText().contains("oauth_login_stale"))
            assertEquals(0, fake.tokenRequests.size)
        }
    }

    @Test
    fun `admin oauth interactive login retries binding after an email conflict`() = runBlocking {
        val channel = createChannel("openai", "ok")
        val conflicting = createChannel("openai-conflict", "ok")
        app.oauthAccountRepository.listAll()
            .filter { it.accountEmail == "login@example.com" }
            .forEach { app.oauthAccountRepository.delete(it.id) }
        val conflictingAccount = createOAuthAccount(
            conflicting,
            "login@example.com",
            refreshToken = "conflict-refresh-token",
        )

        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            val started = client.post("/admin/api/oauth-login/start") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"provider":"chatgpt","channelIds":["${channel.id}"]}""")
            }
            assertEquals(HttpStatusCode.OK, started.status)
            val startedBody = Json.parseToJsonElement(started.bodyAsText()).jsonObject
            val loginId = startedBody["loginId"]!!.jsonPrimitive.content
            val state = startedBody["authorizeUrl"]!!.jsonPrimitive.content
                .substringAfter("state=").substringBefore("&")
            suspend fun complete() = client.post("/admin/api/oauth-login/$loginId/complete") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"callbackUrl":"http://localhost:1455/auth/callback?code=auth-code&state=$state"}""")
            }

            val conflicted = complete()
            assertEquals(HttpStatusCode.Conflict, conflicted.status)
            assertTrue(conflicted.bodyAsText().contains("oauth_account_email_exists"))
            assertEquals(1, fake.tokenRequests.size)

            // 解除原绑定后用同一登录会话重试：不再交换授权码，直接完成绑定。
            app.oauthAccountRepository.delete(conflictingAccount.id)
            val retried = complete()
            assertEquals(HttpStatusCode.OK, retried.status)
            val account = Json.parseToJsonElement(retried.bodyAsText()).jsonObject
            assertEquals("login@example.com", account["accountEmail"]!!.jsonPrimitive.content)
            assertEquals(1, fake.tokenRequests.size)
        }
    }
}
