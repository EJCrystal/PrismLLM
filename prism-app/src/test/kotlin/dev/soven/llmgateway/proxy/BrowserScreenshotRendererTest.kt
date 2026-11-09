package dev.soven.llmgateway.proxy

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BrowserScreenshotRendererTest {
    private fun renderer(maxWidth: Int = 320): BrowserScreenshotRenderer {
        val executable = BrowserScreenshotRenderer.resolveExecutable()
        assumeTrue(executable != null, "本机没有安装 Chrome/Chromium，跳过无头浏览器测试")
        return BrowserScreenshotRenderer(executable!!, maxWidth = maxWidth)
    }

    @Test
    fun `renders css keyframes found outside the svg`() {
        val html = """
            <!DOCTYPE html><html><head><style>
              @keyframes slide { from { transform: translateX(0); } to { transform: translateX(160px); } }
              .box { animation: slide 1s linear infinite; }
            </style></head><body>
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 100">
              <rect class="box" x="0" y="0" width="40" height="40" fill="#e11d48"/>
            </svg>
            </body></html>
        """.trimIndent()
        val renderer = renderer()

        val frames = listOf(0f, 0.25f, 0.5f, 0.75f)
            .map { assertIs<SvgRenderResult.Success>(renderer.renderFrames(html, listOf(it)).single()) }

        assertEquals(320, frames.first().width)
        assertEquals(160, frames.first().height)
        frames.forEach { frame ->
            assertEquals(0x89.toByte(), frame.png[0])
            assertEquals('P'.code.toByte(), frame.png[1])
        }
        assertFalse(frames[0].png.contentEquals(frames[1].png))
        assertFalse(frames[1].png.contentEquals(frames[2].png))
        assertFalse(frames[2].png.contentEquals(frames[3].png))
    }

    @Test
    fun `renders smil frames at different times`() {
        val html = """
            <svg xmlns="http://www.w3.org/2000/svg" width="200" height="100" viewBox="0 0 200 100">
              <rect x="10" y="10" width="40" height="40" fill="#e11d48">
                <animate attributeName="x" from="10" to="140" dur="1s" repeatCount="indefinite"/>
              </rect>
            </svg>
        """.trimIndent()
        val renderer = renderer()

        val frames = listOf(0f, 0.25f, 0.5f, 0.75f)
            .map { assertIs<SvgRenderResult.Success>(renderer.renderFrames(html, listOf(it)).single()) }

        assertFalse(frames[0].png.contentEquals(frames[1].png))
        assertFalse(frames[1].png.contentEquals(frames[2].png))
        assertFalse(frames[2].png.contentEquals(frames[3].png))
    }

    @Test
    fun `static frames are byte-identical`() {
        val html = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 100">
              <rect x="10" y="10" width="40" height="40" fill="#e11d48"/>
            </svg>
        """.trimIndent()
        val renderer = renderer()

        val frames = listOf(0f, 0.5f)
            .map { assertIs<SvgRenderResult.Success>(renderer.renderFrames(html, listOf(it)).single()) }

        assertTrue(frames[0].png.contentEquals(frames[1].png))
    }

    @Test
    fun `rejects html without svg`() {
        val result = renderer().render("<html><body>一只鹈鹕</body></html>")

        assertIs<SvgRenderResult.Failure>(result)
        assertTrue(result.reason.contains("SVG"))
    }

    @Test
    fun `sanitizes scripts and external references before loading`() {
        val html = """
            <html><head><script>document.body.innerHTML = 'broken'</script></head><body>
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 80">
              <image href="https://example.com/pelican.png" width="10" height="10"/>
              <image href="file:///etc/passwd" width="10" height="10"/>
              <rect width="120" height="80" fill="#7dd3fc" onclick="alert(1)"/>
            </svg>
            </body></html>
        """.trimIndent()

        val result = assertIs<SvgRenderResult.Success>(renderer().render(html))

        assertTrue(result.png.isNotEmpty())
    }

    @Test
    fun `keeps the chrome sandbox when the gateway is unprivileged`() {
        val renderer = BrowserScreenshotRenderer(
            executable = Path.of("/usr/bin/chromium"),
            isolation = BrowserIsolation(emptyList(), disableSandbox = false),
        )

        val command = renderer.browserCommand(
            Path.of("/tmp/frame.png"),
            Path.of("/tmp/profile"),
            320,
            200,
            Path.of("/tmp/frame.html"),
        )

        assertEquals("/usr/bin/chromium", command.first())
        assertFalse(command.contains("--no-sandbox"))
    }

    @Test
    fun `drops root browsers to nobody before disabling the sandbox`() {
        val isolation = resolveBrowserIsolation(uid = 0, setpriv = Path.of("/usr/bin/setpriv"))
        val renderer = BrowserScreenshotRenderer(
            executable = Path.of("/usr/bin/chromium"),
            isolation = isolation,
        )

        val command = renderer.browserCommand(
            Path.of("/tmp/frame.png"),
            Path.of("/tmp/profile"),
            320,
            200,
            Path.of("/tmp/frame.html"),
        )

        assertEquals(
            listOf(
                "/usr/bin/setpriv",
                "--reuid=65534",
                "--regid=65534",
                "--clear-groups",
                "--",
                "/usr/bin/chromium",
            ),
            command.take(6),
        )
        assertTrue(command.contains("--no-sandbox"))
    }

    @Test
    fun `browser root is traversable but not writable by other users`() {
        assumeTrue(System.getProperty("os.name").orEmpty().lowercase().contains("linux"))
        val directory = Files.createTempDirectory("iq-vision-browser-test-")
        try {
            val renderer = BrowserScreenshotRenderer(Path.of("/usr/bin/chromium"))

            assertTrue(renderer.prepareBrowserRoot(directory))
            assertEquals(
                "rwx--x--x",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)),
            )
        } finally {
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `cleanup does not follow symbolic links`() {
        val root = Files.createTempDirectory("iq-vision-cleanup-")
        val outside = Files.createTempDirectory("iq-vision-outside-")
        try {
            val keep = outside.resolve("keep.txt")
            Files.writeString(keep, "keep")
            Files.createDirectories(root.resolve("output"))
            Files.writeString(root.resolve("output/frame.png"), "png")
            Files.createSymbolicLink(root.resolve("output/link"), outside)
            Files.createSymbolicLink(root.resolve("file-link"), keep)

            deleteBrowserDirectory(root)

            assertFalse(Files.exists(root))
            assertEquals("keep", Files.readString(keep))
        } finally {
            runCatching { deleteBrowserDirectory(root) }
            runCatching { deleteBrowserDirectory(outside) }
        }
    }

    @Test
    fun `refuses to render as root without a privilege drop`() {
        val isolation = resolveBrowserIsolation(uid = 0, setpriv = null)
        assertNotNull(isolation.unavailableReason)

        val renderer = BrowserScreenshotRenderer(
            executable = Path.of("/usr/bin/chromium"),
            isolation = isolation,
        )
        val result = renderer.render("<svg><rect width=\"10\" height=\"10\"/></svg>")

        assertIs<SvgRenderResult.Failure>(result)
        assertTrue(result.reason.contains("root"))
    }

    @Test
    fun `reports browser label`() {
        val renderer = renderer()

        assertEquals("无头浏览器", renderer.label)
        assertTrue(renderer.available)
    }
}
