package dev.soven.prismllm.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UpstreamHeaderCatalogServiceTest {
    @Test
    fun `parses npm package versions`() {
        assertEquals("2.1.312", parseNpmVersionResponse("""{"version":"2.1.312"}"""))
        assertFailsWith<IllegalStateException> { parseNpmVersionResponse("{}") }
    }

    @Test
    fun `parses Codex rust release tags`() {
        assertEquals("0.165.0", parseCodexReleaseTag("""{"tag_name":"rust-v0.165.0"}"""))
        assertEquals("1.2.3", parseCodexReleaseTag("""{"tag_name":"v1.2.3"}"""))
        assertFailsWith<IllegalStateException> { parseCodexReleaseTag("{}") }
    }

    @Test
    fun `parses the Antigravity updater manifest`() {
        val manifest = """
            version: 2.30.0
            files:
              - url: https://storage.googleapis.com/antigravity-public/antigravity-hub/2.30.0/Antigravity.zip
            path: Antigravity.zip
        """.trimIndent()
        assertEquals("2.30.0", parseAntigravityVersion(manifest))
        assertEquals("2.30.0", parseAntigravityVersion("version: '2.30.0'\r\nfiles: []"))
        assertEquals("2.30.0", parseAntigravityVersion("version: \"2.30.0\""))
        assertFailsWith<IllegalStateException> { parseAntigravityVersion("files: []") }
        assertFailsWith<IllegalStateException> { parseAntigravityVersion("version: not a version") }
        assertFailsWith<IllegalStateException> { parseAntigravityVersion("  version: 2.30.0") }
    }

    @Test
    fun `rejects source responses larger than one MB`() {
        assertEquals("{}", decodeCatalogSource("{}".toByteArray()))
        assertFailsWith<IllegalArgumentException> {
            decodeCatalogSource(ByteArray(1_048_577))
        }
    }

    @Test
    fun `sanitizes catalog sources to controlled labels`() {
        assertEquals("npm:@anthropic-ai/claude-code", sanitizeCatalogSource("npm:@anthropic-ai/claude-code"))
        assertEquals("github:openai/codex", sanitizeCatalogSource("github:openai/codex"))
        assertEquals("antigravity:hub", sanitizeCatalogSource("antigravity:hub"))
        assertEquals("builtin", sanitizeCatalogSource("builtin"))
        assertEquals("manifest", sanitizeCatalogSource("  "))
        assertEquals(
            "https://example.com/catalog",
            sanitizeCatalogSource("https://user:secret@example.com/catalog?token=secret#fragment"),
        )
        assertEquals("custom", sanitizeCatalogSource("token=super-secret"))
        assertEquals("custom", sanitizeCatalogSource("npm:unsafe source"))
    }

    @Test
    fun `rejects duplicate header names ignoring case`() {
        val profile = UpstreamHeaderProfiles.defaults.first().copy(
            headers = linkedMapOf("User-Agent" to "one", "user-agent" to "two"),
        )
        assertEquals("profile 请求头名称重复", upstreamHeaderProfileValidationError(profile))
    }
}
