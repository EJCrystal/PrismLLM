package dev.soven.llmgateway.config

import dev.soven.llmgateway.domain.OAuthRoutingStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun `antigravity stays import only unless login parameters are added`() {
        val antigravity = OAuthConfig.fromEnv(mapOf("OAUTH_ANTIGRAVITY_CLIENT_ID" to "client")).antigravity!!
        assertFalse(antigravity.supportsLogin)
    }
}
