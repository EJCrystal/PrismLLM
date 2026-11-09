package dev.soven.llmgateway.plugin.iq

import dev.soven.llmgateway.spi.iq.IqJudge
import org.slf4j.LoggerFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceConfigurationError
import java.util.ServiceLoader

/**
 * 从内置判分器和可选插件目录发现 `IqJudge`。
 *
 * 外部 JAR 使用父优先的类加载器，只能实现当前网关暴露的 SPI；同一 ID 只保留第一个
 * 成功加载的实现，后续重复项会被忽略并记录日志。
 */
class IqJudgeRegistry(
    pluginDirectory: Path? = null,
    parentClassLoader: ClassLoader = IqJudgeRegistry::class.java.classLoader,
    builtIns: Iterable<IqJudge> = emptyList(),
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(IqJudgeRegistry::class.java)
    private val pluginClassLoader: URLClassLoader?
    private val judgesById: LinkedHashMap<String, IqJudge> = LinkedHashMap()
    private val metadataById: LinkedHashMap<String, IqJudgeInfo> = LinkedHashMap()
    private val failures = mutableListOf<IqJudgePluginFailure>()

    init {
        builtIns.forEach { register(it, "built-in", builtIn = true) }
        val jars = pluginDirectory?.let(::pluginJars).orEmpty()
        pluginClassLoader = if (jars.isEmpty()) {
            null
        } else {
            URLClassLoader(jars.map { it.toUri().toURL() }.toTypedArray(), parentClassLoader).also { loader ->
                load(loader, pluginDirectory!!.toAbsolutePath().toString())
            }
        }
        if (jars.isEmpty() && pluginDirectory != null && Files.exists(pluginDirectory)) {
            logger.info("no IQ judge plugin JARs found in {}", pluginDirectory.toAbsolutePath())
        }
    }

    val judges: List<IqJudge>
        get() = judgesById.values.toList()

    val judgeInfos: List<IqJudgeInfo>
        get() = metadataById.values.toList()

    val loadFailures: List<IqJudgePluginFailure>
        get() = failures.toList()

    fun find(id: String): IqJudge? = judgesById[id]

    private fun load(classLoader: ClassLoader, source: String) {
        val serviceLoader = ServiceLoader.load(IqJudge::class.java, classLoader)
        val iterator = serviceLoader.iterator()
        while (true) {
            try {
                if (!iterator.hasNext()) break
            } catch (cause: ServiceConfigurationError) {
                recordFailure(source, "插件服务声明无效", cause)
                continue
            }
            val judge = try {
                iterator.next()
            } catch (cause: ServiceConfigurationError) {
                recordFailure(source, cause.message ?: cause.javaClass.simpleName, cause)
                continue
            } catch (cause: LinkageError) {
                recordFailure(source, cause.message ?: cause.javaClass.simpleName, cause)
                continue
            }
            register(judge, source, builtIn = false)
        }
    }

    private fun register(judge: IqJudge, source: String, builtIn: Boolean) {
        val metadata = runCatching {
            JudgeMetadata(judge.id, judge.apiVersion, judge.displayName, judge.description)
        }.getOrElse {
            recordFailure(source, "读取判分器 ID 失败", it)
            closeRejected(judge)
            return
        }
        val id = metadata.id
        if (!Regex(IqJudge.ID_PATTERN).matches(id)) {
            recordFailure(source, "判分器 ID 不合法: $id", null)
            closeRejected(judge)
            return
        }
        if (metadata.apiVersion != IqJudge.API_VERSION) {
            recordFailure(source, "判分器 $id 使用不支持的 SPI 版本 " + metadata.apiVersion, null)
            closeRejected(judge)
            return
        }
        val displayName = metadata.displayName
        if (displayName.isBlank() || displayName.length > 120 || displayName.any { it.isISOControl() }) {
            recordFailure(source, "判分器 $id 的展示名称无效", null)
            closeRejected(judge)
            return
        }
        if (judgesById.putIfAbsent(id, judge) != null) {
            recordFailure(source, "重复的判分器 ID: $id", null)
            closeRejected(judge)
            return
        }
        metadataById[id] = IqJudgeInfo(id, displayName, metadata.apiVersion, builtIn, metadata.description.take(300))
        logger.info("loaded IQ judge id={} name={} source={}", id, displayName, source)
    }

    private fun closeRejected(judge: IqJudge) {
        if (judgesById.values.none { it === judge }) runCatching { judge.close() }
    }

    private fun recordFailure(source: String, message: String, cause: Throwable?) {
        failures += IqJudgePluginFailure(source, message)
        if (cause == null) logger.warn("unable to load IQ judge: source={} message={}", source, message)
        else logger.warn("unable to load IQ judge: source={} message={}", source, message, cause)
    }

    private fun pluginJars(directory: Path): List<Path> {
        if (!Files.exists(directory)) return emptyList()
        if (!Files.isDirectory(directory)) {
            recordFailure(directory.toAbsolutePath().toString(), "插件路径不是目录", null)
            return emptyList()
        }
        return try {
            Files.list(directory).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().lowercase().endsWith(".jar") }
                    .sorted()
                    .toList()
            }
        } catch (cause: Exception) {
            recordFailure(directory.fileName.toString(), "无法读取插件目录", cause)
            emptyList()
        }
    }

    override fun close() {
        judgesById.values.forEach { judge ->
            runCatching { judge.close() }.onFailure { logger.warn("failed to close IQ judge", it) }
        }
        runCatching { pluginClassLoader?.close() }
            .onFailure { logger.warn("failed to close IQ judge plugin classloader", it) }
    }
}

data class IqJudgePluginFailure(
    val source: String,
    val message: String,
)

data class IqJudgeInfo(
    val id: String,
    val displayName: String,
    val apiVersion: Int,
    val builtIn: Boolean,
    val description: String = "",
)

private data class JudgeMetadata(
    val id: String,
    val apiVersion: Int,
    val displayName: String,
    val description: String,
)
