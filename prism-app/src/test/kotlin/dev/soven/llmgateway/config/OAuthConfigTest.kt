package dev.soven.llmgateway.config

import dev.soven.llmgateway.oauth.domain.OAuthRoutingStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OAuthConfigTest {
    @Test
    fun `chatgpt login defaults are usable out of the box`() {
        val chatGpt = OAuthConfig.fromEnv(emptyMap()).chatGpt
        assertTrue(chatGpt.supportsLogin)
        assertEquals("https://auth.openai.com/oauth/authorize", chatGpt.authorizeUrl)
        assertEquals("http://localhost:1455/auth/callback", chatGpt.redirectUri)
        assertTrue(chatGpt.loginScopes!!.contains("offline_access"))
    }

    @Test
    fun `loopback redirects accept ipv4 ipv6 and localhost over http`() {
        for (redirect in listOf(
            "http://127.0.0.1:1455/auth/callback",
            "http://localhost:1455/auth/callback",
            "http://[::1]:1455/auth/callback",
        )) {
            val chatGpt = OAuthConfig.fromEnv(mapOf("OAUTH_CHATGPT_REDIRECT_URI" to redirect)).chatGpt
            assertEquals(redirect, chatGpt.redirectUri)
        }
    }

    @Test
    fun `non loopback redirects require https and no query`() {
        assertFailsWith<IllegalArgumentException> {
            OAuthConfig.fromEnv(mapOf("OAUTH_CHATGPT_REDIRECT_URI" to "http://gateway.example.com/callback"))
        }
        assertFailsWith<IllegalArgumentException> {
            OAuthConfig.fromEnv(mapOf("OAUTH_CHATGPT_REDIRECT_URI" to "https://gateway.example.com/callback?next=1"))
        }
        val secure = OAuthConfig.fromEnv(
            mapOf("OAUTH_CHATGPT_REDIRECT_URI" to "https://gateway.example.com/callback"),
        ).chatGpt
        assertTrue(secure.supportsLogin)
    }

    @Test
    fun `routing defaults to round robin and rejects unknown strategies`() {
        assertEquals(OAuthRoutingStrategy.ROUND_ROBIN, OAuthConfig.fromEnv(emptyMap()).routing)
        assertEquals(
            OAuthRoutingStrategy.DRAIN,
            OAuthConfig.fromEnv(mapOf("OAUTH_ROUTING" to "DRAIN")).routing,
        )
        assertFailsWith<IllegalStateException> {
            OAuthConfig.fromEnv(mapOf("OAUTH_ROUTING" to "fastest"))
        }
    }

    @Test
    fun `proxy allowlist is empty by default and normalizes configured hosts`() {
        assertEquals(emptySet(), OAuthConfig.fromEnv(emptyMap()).proxyAllowedHosts)
        assertEquals(
            setOf("proxy.example.com", "10.0.0.12"),
            OAuthConfig.fromEnv(
                mapOf("OAUTH_PROXY_ALLOWED_HOSTS" to " Proxy.Example.com , 10.0.0.12 "),
            ).proxyAllowedHosts,
        )
        assertEquals(
            emptySet(),
            OAuthConfig.fromEnv(mapOf("OAUTH_PROXY_ALLOWED_HOSTS" to " , ")).proxyAllowedHosts,
        )
    }

    @Test
    fun `proxy allowlist rejects schemes ports paths credentials and oversized lists`() {
        for (bad in listOf(
            "http://proxy.example.com",
            "proxy.example.com:8080",
            "user:pass@proxy.example.com",
            "proxy.example.com/path",
            "*.example.com",
        )) {
            assertFailsWith<IllegalArgumentException> {
                OAuthConfig.fromEnv(mapOf("OAUTH_PROXY_ALLOWED_HOSTS" to bad))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            OAuthConfig.fromEnv(
                mapOf("OAUTH_PROXY_ALLOWED_HOSTS" to (1..33).joinToString(",") { "p$it.example.com" }),
            )
        }
    }

    @Test
    fun `antigravity ships public client credentials and needs no configuration`() {
        val antigravity = OAuthConfig.fromEnv(emptyMap()).antigravity
        assertEquals(OAuthConfig.DEFAULT_ANTIGRAVITY_CLIENT_ID, antigravity.clientId)
        assertEquals(OAuthConfig.DEFAULT_ANTIGRAVITY_CLIENT_SECRET, antigravity.clientSecret)
        assertEquals("https://oauth2.googleapis.com/token", antigravity.tokenEndpoint)
        assertEquals("https://accounts.google.com/o/oauth2/v2/auth", antigravity.authorizeUrl)
        assertEquals("http://localhost:8085/callback", antigravity.redirectUri)
        assertTrue(antigravity.supportsLogin)
        assertTrue(antigravity.loginScopes!!.contains("cloud-platform"))
    }

    @Test
    fun `antigravity client credentials can be overridden by environment`() {
        val antigravity = OAuthConfig.fromEnv(
            mapOf(
                "OAUTH_ANTIGRAVITY_CLIENT_ID" to "custom-client",
                "OAUTH_ANTIGRAVITY_CLIENT_SECRET" to "custom-secret",
                "OAUTH_ANTIGRAVITY_TOKEN_ENDPOINT" to "https://token.example.com/token",
            ),
        ).antigravity
        assertEquals("custom-client", antigravity.clientId)
        assertEquals("custom-secret", antigravity.clientSecret)
        assertEquals("https://token.example.com/token", antigravity.tokenEndpoint)
    }

    @Test
    fun `blank antigravity overrides fall back to the built-in client`() {
        val antigravity = OAuthConfig.fromEnv(
            mapOf("OAUTH_ANTIGRAVITY_CLIENT_ID" to " ", "OAUTH_ANTIGRAVITY_CLIENT_SECRET" to ""),
        ).antigravity
        assertEquals(OAuthConfig.DEFAULT_ANTIGRAVITY_CLIENT_ID, antigravity.clientId)
        assertEquals(OAuthConfig.DEFAULT_ANTIGRAVITY_CLIENT_SECRET, antigravity.clientSecret)
    }
}
