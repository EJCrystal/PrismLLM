package dev.soven.llmgateway.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OAuthLoginCallbackTest {
    @Test
    fun `callback parser accepts full urls bare queries and fragments`() {
        assertEquals(
            ParsedCallback(code = "abc", state = "s1", error = null),
            parseCallback("http://localhost:1455/auth/callback?code=abc&state=s1"),
        )
        assertEquals(
            ParsedCallback(code = "abc", state = "s1", error = null),
            parseCallback("code=abc&state=s1"),
        )
        assertEquals(
            ParsedCallback(code = "abc", state = "s1", error = null),
            parseCallback("?code=abc&state=s1"),
        )
        assertEquals(
            ParsedCallback(code = "ab+c", state = "s 1", error = null),
            parseCallback("code=ab%2Bc&state=s+1"),
        )
        assertEquals(
            ParsedCallback(code = null, state = "s1", error = "access_denied"),
            parseCallback("http://localhost:1455/auth/callback?error=access_denied&state=s1"),
        )
    }

    @Test
    fun `callback parser rejects inputs without code or error`() {
        assertNull(parseCallback(""))
        assertNull(parseCallback("   "))
        assertNull(parseCallback("auth-code-only"))
        assertNull(parseCallback("http://localhost:1455/auth/callback"))
        // 超长输入被截断，不会把整段文本带进解析。
        assertNull(parseCallback("x".repeat(10_000)))
    }
}
