package dev.soven.llmgateway.proxy

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 用系统安装的无头 Chrome/Chromium 逐帧截取内联 SVG。
 *
 * 每个时间点独立启动一次浏览器，并在页面加载后把 CSS 动画（Web Animations API）和
 * SMIL 时间线都定位到目标时刻，因此 CSS `@keyframes` 与 SVG SMIL 都能被真实采样。
 *
 * 模型输出先经过统一的 SVG 清洗，再放进带 CSP nonce 的最小 HTML 文档：
 * 仅允许内联样式、data 图片和注入的定位脚本；外链、脚本标签和事件属性会被移除，
 * 浏览器还会被限制为无法解析外部域名。浏览器进程以 `--no-sandbox` 运行，
 * 因为网关容器通常以 root 启动；页面内容本身不执行模型提供的脚本。
 */
class BrowserScreenshotRenderer(
    private val executable: Path,
    private val maxWidth: Int = 1024,
    private val maxImageBytes: Int = 4_000_000,
    private val timeoutMs: Long = 15_000,
) : HtmlImageRenderer {
    override val label: String = "无头浏览器"

    override fun render(html: String): SvgRenderResult =
        renderFrames(html, listOf(0f)).single()

    override fun renderFrames(html: String, times: List<Float>): List<SvgRenderResult> {
        if (times.isEmpty()) return emptyList()
        val svg = extractInlineSvg(html)
            ?: return times.map { SvgRenderResult.Failure("未找到可渲染的内联 SVG") }
        val sanitizedSvg = SvgRenderSupport.sanitizeSvg(svg)
        val styles = extractStyles(html, svg)
        val size = SvgRenderSupport.svgSize(sanitizedSvg, minOf(maxWidth, MULTI_FRAME_WIDTH))
        val width = size.first.coerceIn(160, minOf(maxWidth, MULTI_FRAME_WIDTH))
        val height = size.second
        val directory = Files.createTempDirectory("iq-vision-browser-")
        return try {
            times.mapIndexed { index, time ->
                renderFrame(directory, index, sanitizedSvg, styles, width, height, time)
            }
        } finally {
            runCatching { directory.toFile().deleteRecursively() }
        }
    }

    private fun renderFrame(
        directory: Path,
        index: Int,
        svg: String,
        styles: String,
        width: Int,
        height: Int,
        timeSeconds: Float,
    ): SvgRenderResult {
        val htmlFile = directory.resolve("frame-$index.html")
        val pngFile = directory.resolve("frame-$index.png")
        val profileDirectory = directory.resolve("profile-$index")
        var process: Process? = null
        return try {
            Files.createDirectories(profileDirectory)
            val nonce = "iq-vision-" + UUID.randomUUID().toString().replace("-", "")
            Files.writeString(
                htmlFile,
                buildDocument(svg, styles, width, height, timeSeconds, nonce),
                Charsets.UTF_8,
            )
            val started = ProcessBuilder(
                browserCommand(pngFile, profileDirectory, width, height, htmlFile),
            )
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            process = started
            if (!started.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                terminate(started)
                return SvgRenderResult.Failure("无头浏览器渲染超时（${timeoutMs}ms）")
            }
            if (started.exitValue() != 0) {
                return SvgRenderResult.Failure("无头浏览器退出码 ${started.exitValue()}")
            }
            if (!Files.isRegularFile(pngFile)) {
                return SvgRenderResult.Failure("无头浏览器没有生成截图")
            }
            val png = Files.readAllBytes(pngFile)
            when {
                !isPng(png) -> SvgRenderResult.Failure("无头浏览器没有生成有效的 PNG")
                png.size > maxImageBytes -> SvgRenderResult.Failure("截图超过 ${maxImageBytes / 1024} KiB")
                else -> SvgRenderResult.Success(png, width, height)
            }
        } catch (cause: InterruptedException) {
            process?.let { terminate(it) }
            Thread.currentThread().interrupt()
            SvgRenderResult.Failure("无头浏览器渲染被中断")
        } catch (cause: Throwable) {
            process?.let { terminate(it) }
            if (cause is VirtualMachineError) throw cause
            SvgRenderResult.Failure(
                "无头浏览器渲染失败：${cause.message?.take(200) ?: cause::class.simpleName}",
            )
        }
    }

    private fun browserCommand(
        pngFile: Path,
        profileDirectory: Path,
        width: Int,
        height: Int,
        htmlFile: Path,
    ): List<String> = listOf(
        executable.toString(),
        "--headless",
        // 容器通常以 root 运行，Chrome 的沙箱在 root 下无法启动。
        "--no-sandbox",
        "--disable-gpu",
        "--disable-dev-shm-usage",
        "--hide-scrollbars",
        "--force-device-scale-factor=1",
        "--run-all-compositor-stages-before-draw",
        "--disable-background-networking",
        "--disable-component-update",
        "--disable-default-apps",
        "--disable-extensions",
        "--disable-sync",
        "--metrics-recording-only",
        "--mute-audio",
        "--no-first-run",
        "--safebrowsing-disable-auto-update",
        "--no-proxy-server",
        // 页面已经过清洗，并且 CSP 禁止外部资源；这里再阻断 DNS，避免任何漏网请求。
        "--host-resolver-rules=MAP * ~NOTFOUND",
        "--window-size=$width,$height",
        "--screenshot=${pngFile.toAbsolutePath()}",
        "--user-data-dir=${profileDirectory.toAbsolutePath()}",
        htmlFile.toUri().toString(),
    )

    private fun buildDocument(
        svg: String,
        styles: String,
        width: Int,
        height: Int,
        timeSeconds: Float,
        nonce: String,
    ): String = buildString {
        append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        append(
            "<meta http-equiv=\"Content-Security-Policy\" content=\"" +
                "default-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src data:; " +
                "script-src 'nonce-$nonce'; base-uri 'none'; form-action 'none'\">",
        )
        if (styles.isNotBlank()) append("<style>$styles</style>")
        append("<style>")
        append("html,body{margin:0;padding:0;background:#fff;overflow:hidden}")
        append(
            "#iq-stage{width:${width}px;height:${height}px;display:flex;align-items:center;" +
                "justify-content:center;overflow:hidden;background:#fff}",
        )
        append("#iq-stage>svg{display:block;width:100%;height:100%}")
        append("</style></head><body><div id=\"iq-stage\">$svg</div>")
        append("<script nonce=\"$nonce\">")
        append("addEventListener('load',function(){")
        append("var t=${timeSeconds.coerceAtLeast(0f)};")
        append("if(document.getAnimations){document.getAnimations().forEach(function(a){")
        append("try{a.pause();a.currentTime=t*1000}catch(e){}})}")
        append("document.querySelectorAll('svg').forEach(function(s){")
        append("try{s.pauseAnimations();s.setCurrentTime(t)}catch(e){}});")
        append("document.documentElement.getBoundingClientRect();")
        append("});")
        append("</script></body></html>")
    }

    private fun extractStyles(html: String, svg: String): String {
        // 只取 SVG 之外的 <style>；SVG 内部的样式已经在 svg 字符串里。
        val outside = html.replace(svg, "")
        return Regex("(?is)<style\\b[^>]*>(.*?)</style>")
            .findAll(outside)
            .map { SvgRenderSupport.sanitizeCss(it.groupValues[1]).trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n")
    }

    private fun terminate(process: Process) {
        runCatching { process.descendants().forEach { it.destroyForcibly() } }
        process.destroyForcibly()
        runCatching { process.waitFor(2, TimeUnit.SECONDS) }
    }

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() &&
            bytes[3] == 'G'.code.toByte()

    companion object {
        private val COMMON_BROWSERS = listOf(
            "/usr/bin/google-chrome-stable",
            "/usr/bin/google-chrome",
            "/usr/bin/chromium",
            "/usr/bin/chromium-browser",
            "/opt/google/chrome/chrome",
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/Applications/Chromium.app/Contents/MacOS/Chromium",
            "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
            "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
        )

        /**
         * 解析浏览器可执行文件。显式配置优先；否则依次检查常见环境变量和系统路径。
         *
         * @param configured `IQ_VISION_BROWSER_PATH`，可以是绝对路径，也可以是 PATH 中的命令名。
         */
        fun resolveExecutable(configured: String = ""): Path? {
            if (configured.isNotBlank()) return executablePath(configured)
            val fromEnvironment = listOf("CHROME_PATH", "GOOGLE_CHROME_BIN", "CHROMIUM_PATH", "BROWSER_PATH")
                .mapNotNull { System.getenv(it)?.takeIf(String::isNotBlank) }
            return (fromEnvironment + COMMON_BROWSERS).firstNotNullOfOrNull { executablePath(it) }
        }

        private fun executablePath(raw: String): Path? {
            val candidates = if (raw.contains('/') || raw.contains('\\')) {
                listOf(Path.of(raw))
            } else {
                System.getenv("PATH").orEmpty()
                    .split(File.pathSeparator)
                    .filter(String::isNotBlank)
                    .map { Path.of(it, raw) }
            }
            return candidates
                .map { it.toAbsolutePath().normalize() }
                .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
        }
    }
}

/**
 * 未找到浏览器时的占位渲染器。
 *
 * 视觉判分不会降级到其他渲染器，而是直接返回人工确认。
 */
object UnavailableBrowserRenderer : HtmlImageRenderer {
    const val REASON: String = "未找到可用的无头浏览器，无法逐帧渲染动画截图。"
    override val label: String = "无可用浏览器"
    override val available: Boolean = false

    override fun render(html: String): SvgRenderResult = SvgRenderResult.Failure(REASON)

    override fun renderFrames(html: String, times: List<Float>): List<SvgRenderResult> =
        times.map { SvgRenderResult.Failure(REASON) }
}
