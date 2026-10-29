package dev.soven.llmgateway.proxy

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
}
