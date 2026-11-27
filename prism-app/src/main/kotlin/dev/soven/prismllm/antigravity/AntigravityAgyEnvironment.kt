package dev.soven.prismllm.antigravity

import dev.soven.prismllm.config.AntigravityAgyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

internal class AgyPreparationException(message: String) : RuntimeException(message)

internal class AntigravityAgyEnvironment(
    private val executable: (Path) -> Boolean = Files::isExecutable,
    private val command: suspend (List<String>, String, Long) -> String = ::runAgyPreparationCommand,
) {
    suspend fun prepare(
        config: AntigravityAgyConfig,
        report: (String, List<String>) -> Unit,
        enabled: () -> Boolean,
    ) {
        report("CHECKING", emptyList())
        if (System.getProperty("os.name") != "Linux" || System.getProperty("os.arch") !in setOf("amd64", "x86_64")) {
            throw AgyPreparationException("官方 agy 自动准备仅支持 Linux x86_64")
        }
        if (!Files.isRegularFile(config.script) || !Files.isRegularFile(config.script.resolveSibling("antigravity_acp_pilot.py")) ||
            !Files.isRegularFile(config.script.resolveSibling("antigravity-acp-release.json"))) {
            throw AgyPreparationException("部署缺少随应用发布的 agy 脚本或固定版本清单，请更新完整的应用镜像")
        }
        val state = config.stateDir
        if (generateSequence(state) { it.parent }.any(Files::isSymbolicLink)) {
            throw AgyPreparationException("agy 状态目录或上级目录不能是符号链接")
        }
        if (!Files.exists(state, NOFOLLOW_LINKS)) {
            Files.createDirectories(state, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        }
        if (!Files.isDirectory(state, NOFOLLOW_LINKS) || Files.getOwner(state, NOFOLLOW_LINKS).name != System.getProperty("user.name") ||
            Files.getPosixFilePermissions(state, NOFOLLOW_LINKS) != PosixFilePermissions.fromString("rwx------")) {
            throw AgyPreparationException("agy 状态目录必须归服务用户所有且权限为 0700，请修复部署目录权限后重试")
        }
        val missing = buildList {
            if (!pythonSupported(config)) add("Python 3.11+")
            if (!executable(Path.of("/usr/bin/bwrap"))) add("Bubblewrap")
        }
        if (!enabled()) return
        if (missing.isNotEmpty()) {
            report("INSTALLING", missing)
            if (!enabled()) return
            if (!executable(Path.of("/usr/bin/apt-get"))) {
                throw AgyPreparationException("缺少 " + missing.joinToString("、") + "；当前环境不支持 apt-get 自动安装，请由部署管理员安装后重试")
            }
            if (command(listOf("/usr/bin/id", "-u"), config.proxy, 10).trim() != "0") {
                throw AgyPreparationException("缺少 " + missing.joinToString("、") + "；服务用户没有系统安装权限，请由部署管理员安装后重试，不会自动提权")
            }
            if ("Python 3.11+" in missing && config.python != Path.of("/usr/bin/python3")) {
                throw AgyPreparationException("部署指定的 Python 不可用，请由部署管理员修复该解释器后重试")
            }
            command(listOf("/usr/bin/apt-get", "update"), config.proxy, 300)
            if (!enabled()) return
            val packages = buildList {
                if ("Python 3.11+" in missing) add("python3")
                if ("Bubblewrap" in missing) add("bubblewrap")
            }
            command(listOf("/usr/bin/apt-get", "install", "-y", "--no-install-recommends") + packages, config.proxy, 600)
            if (!enabled()) return
            if (!pythonSupported(config) || !executable(Path.of("/usr/bin/bwrap"))) {
                throw AgyPreparationException("安装后依赖仍不满足要求；系统软件源需提供 Python 3.11+ 和 Bubblewrap，请更新运行环境后重试")
            }
        }
        if (!enabled()) return
        report("PREPARING", missing)
        try {
            command(config.preparationCommand(), config.proxy, 180)
        } catch (failure: AgyPreparationException) {
            throw AgyPreparationException("agy 下载或隔离检查失败：请检查本机 HTTP 代理、官方发行包下载，以及宿主机是否允许 Bubblewrap 用户/网络命名空间；不会禁用沙盒或回退旧 OAuth")
        }
    }

    private suspend fun pythonSupported(config: AntigravityAgyConfig): Boolean {
        if (!executable(config.python)) return false
        val version = try {
            command(listOf(config.python.toString(), "--version"), config.proxy, 10)
        } catch (failure: AgyPreparationException) {
            return false
        } catch (failure: java.io.IOException) {
            return false
        }
        val match = Regex("Python ([0-9]+)\\.([0-9]+)").find(version) ?: return false
        return match.groupValues[1].toInt() == 3 && match.groupValues[2].toInt() >= 11
    }
}

private suspend fun runAgyPreparationCommand(command: List<String>, proxy: String, timeoutSeconds: Long): String {
    val capture = "--version" in command || command.first() == "/usr/bin/id"
    val output = if (capture) Files.createTempFile("prism-agy-check-", ".txt") else null
    try {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply {
                environment().apply {
                    clear()
                    put("PATH", "/usr/sbin:/usr/bin:/sbin:/bin")
                    put("LANG", "C.UTF-8")
                    put("DEBIAN_FRONTEND", "noninteractive")
                    if (command.first() == "/usr/bin/apt-get") {
                        put("http_proxy", proxy)
                        put("https_proxy", proxy)
                    }
                }
                if (output == null) redirectOutput(ProcessBuilder.Redirect.DISCARD) else redirectOutput(output.toFile())
            }
            .start()
        try {
            val finished = runInterruptible(Dispatchers.IO) { process.waitFor(timeoutSeconds, TimeUnit.SECONDS) }
            if (!finished || process.exitValue() != 0) {
                throw AgyPreparationException("依赖下载或安装失败，请检查软件源、代理、磁盘空间及服务用户安装权限后重试")
            }
            if (output == null) return ""
            if (Files.size(output) > 4096) throw AgyPreparationException("运行依赖检查返回了异常输出")
            return Files.readString(output)
        } finally {
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(8, TimeUnit.SECONDS)) {
                    process.descendants().forEach { it.destroyForcibly() }
                    process.destroyForcibly()
                }
            }
        }
    } finally {
        output?.let(Files::deleteIfExists)
    }
}
