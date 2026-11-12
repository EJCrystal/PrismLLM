package dev.soven.prismllm.proxy

import dev.soven.prismllm.shared.error.GatewayError
import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.shared.domain.UpstreamProtocolOverrides
import dev.soven.prismllm.shared.domain.UpstreamRequestOverrides
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class UpstreamModelsTest {
    @Test
    fun `Gemini follows encoded page tokens with consistent headers and deduplicates normalized models`() = testApplication {
        val token = "next +/&=?#%"
        val requestedTokens = mutableListOf<String?>()
        val credentials = mutableListOf<String?>()
        val userAgents = mutableListOf<String?>()
        val clients = mutableListOf<String?>()
        externalServices {
            hosts(UPSTREAM_HOST) {
                routing {
                    get("/v1beta/models") {
                        assertEquals("1000", call.request.queryParameters["pageSize"])
                        assertNull(call.request.queryParameters["key"])
                        val requestedToken = call.request.queryParameters["pageToken"]
                        requestedTokens += requestedToken
                        credentials += call.request.headers["x-goog-api-key"]
                        userAgents += call.request.headers["User-Agent"]
                        clients += call.request.headers["X-Client"]
                        call.respondText(
                            if (requestedToken == null) {
                                """{"models":[{"name":"models/z"},{"name":"models/a"}],"nextPageToken":"$token"}"""
                            } else {
                                """{"models":[{"name":"models/a"},{"name":"z"},{"name":"models/m"}]}"""
                            },
                            ContentType.Application.Json,
                        )
                    }
                }
            }
        }
        val models = fetchUpstreamModels(
            "$UPSTREAM_HOST/v1beta/", "upstream-secret", client, authType = "google", protocol = GatewayProtocol.GEMINI,
            globalOverrides = UpstreamRequestOverrides(userAgent = "global-UA", headers = mapOf("X-Client" to "global")),
            channelOverrides = UpstreamRequestOverrides(
                userAgent = "channel-UA",
                protocols = mapOf("gemini" to UpstreamProtocolOverrides(headers = mapOf("X-Client" to "channel-gemini"))),
            ),
        )
        assertEquals(listOf("a", "m", "z"), models.map { it.id })
        assertEquals(listOf(null, token), requestedTokens)
        assertEquals(listOf<String?>("upstream-secret", "upstream-secret"), credentials)
        assertEquals(listOf<String?>("channel-UA", "channel-UA"), userAgents)
        assertEquals(listOf<String?>("channel-gemini", "channel-gemini"), clients)
    }

    @Test
    fun `Gemini enforces the two MB budget across pages`() = testApplication {
        var requests = 0
        val padding = "x".repeat(1_050_000)
        externalServices {
            hosts(UPSTREAM_HOST) {
                routing {
                    get("/v1beta/models") {
                        requests++
                        call.respondText(
                            """{"models":[{"name":"models/m$requests"}],"nextPageToken":"page$requests","padding":"$padding"}""",
                            ContentType.Application.Json,
                        )
                    }
                }
            }
        }
        val error = assertFailsWith<GatewayError> {
            fetchUpstreamModels("$UPSTREAM_HOST/v1beta", "secret", client, protocol = GatewayProtocol.GEMINI)
        }
        assertEquals("upstream_models_too_large", error.code)
        assertEquals(HttpStatusCode.BadGateway, error.status)
        assertEquals(2, requests)
    }

    @Test
    fun `Gemini rejects repeated page tokens instead of looping or returning a partial list`() = testApplication {
        var requests = 0
        externalServices {
            hosts(UPSTREAM_HOST) {
                routing {
                    get("/v1beta/models") {
                        requests++
                        call.respondText(
                            """{"models":[{"name":"models/m$requests"}],"nextPageToken":"repeat"}""",
                            ContentType.Application.Json,
                        )
                    }
                }
            }
        }
        val error = assertFailsWith<GatewayError> {
            fetchUpstreamModels("$UPSTREAM_HOST/v1beta", "secret", client, protocol = GatewayProtocol.GEMINI)
        }
        assertEquals("upstream_models_invalid", error.code)
        assertEquals(2, requests)
    }

    @Test
    fun `a failed later Gemini page fails discovery instead of returning earlier models`() = testApplication {
        var requests = 0
        externalServices {
            hosts(UPSTREAM_HOST) {
                routing {
                    get("/v1beta/models") {
                        requests++
                        if (requests == 1) {
                            call.respondText(
                                """{"models":[{"name":"models/m1"}],"nextPageToken":"next"}""",
                                ContentType.Application.Json,
                            )
                        } else {
                            call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
                        }
                    }
                }
            }
        }
        val error = assertFailsWith<GatewayError> {
            fetchUpstreamModels("$UPSTREAM_HOST/v1beta", "secret", client, protocol = GatewayProtocol.GEMINI)
        }
        assertEquals("upstream_models_error", error.code)
        assertEquals(HttpStatusCode.BadGateway, error.status)
        assertEquals(2, requests)
    }

    @Test
    fun `OpenAI discovery remains a single request without Gemini pagination parameters`() = testApplication {
        var requests = 0
        externalServices {
            hosts(UPSTREAM_HOST) {
                routing {
                    get("/v1/models") {
                        requests++
                        assertNull(call.request.queryParameters["pageSize"])
                        assertNull(call.request.queryParameters["pageToken"])
                        call.respondText(
                            """{"data":[{"id":"z"},{"id":"a"},{"id":"z"}],"nextPageToken":"ignored"}""",
                            ContentType.Application.Json,
                        )
                    }
                }
            }
        }
        val models = fetchUpstreamModels("$UPSTREAM_HOST/v1/", "secret", client, protocol = GatewayProtocol.CHAT_COMPLETIONS)
        assertEquals(listOf("a", "z"), models.map { it.id })
        assertEquals(1, requests)
    }

    @Test
    fun `codex models endpoint appends client_version and parses slug visibility and display name`() = testApplication {
        var requestedClientVersion: String? = null
        externalServices {
            hosts(UPSTREAM_HOST) {
                routing {
                    get("/backend-api/codex/models") {
                        requestedClientVersion = call.request.queryParameters["client_version"]
                        call.respondText(
                            """{"models":[{"slug":"z-luna","visibility":"list","display_name":"Luna"},{"slug":"a-terra","visibility":"list"},{"slug":"hidden","visibility":"hide"}]}""",
                            ContentType.Application.Json,
                        )
                    }
                }
            }
        }
        val models = fetchUpstreamModels(
            "$UPSTREAM_HOST/backend-api/codex", "secret", client, authType = "bearer", protocol = GatewayProtocol.RESPONSES,
        )
        assertEquals("0.165.0", requestedClientVersion)
        assertEquals(listOf("a-terra", "z-luna"), models.map { it.id })
        assertEquals("Luna", models.first { it.id == "z-luna" }.name)
    }

    @Test
    fun `codex models endpoint honors channel version header override`() = testApplication {
        var requestedClientVersion: String? = null
        externalServices {
            hosts(UPSTREAM_HOST) {
                routing {
                    get("/backend-api/codex/models") {
                        requestedClientVersion = call.request.queryParameters["client_version"]
                        call.respondText("""{"models":[{"slug":"m"}]}""", ContentType.Application.Json)
                    }
                }
            }
        }
        fetchUpstreamModels(
            "$UPSTREAM_HOST/backend-api/codex", "secret", client, authType = "bearer", protocol = GatewayProtocol.RESPONSES,
            globalOverrides = UpstreamRequestOverrides(userAgent = "codex_cli_rs/0.42.0 (Linux; x86_64)"),
        )
        assertEquals("0.42.0", requestedClientVersion)
    }

    @Test
    fun `non codex responses endpoint keeps a parameterless models request`() = testApplication {
        var sawClientVersion = false
        externalServices {
            hosts(UPSTREAM_HOST) {
                routing {
                    get("/v1/models") {
                        sawClientVersion = call.request.queryParameters.contains("client_version")
                        call.respondText("""{"data":[{"id":"m"}]}""", ContentType.Application.Json)
                    }
                }
            }
        }
        fetchUpstreamModels("$UPSTREAM_HOST/v1", "secret", client, protocol = GatewayProtocol.RESPONSES)
        assertFalse(sawClientVersion)
    }
}

private const val UPSTREAM_HOST = "https://models.example.com"
