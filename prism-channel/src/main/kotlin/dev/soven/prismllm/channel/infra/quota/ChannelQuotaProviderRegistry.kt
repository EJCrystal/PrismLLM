package dev.soven.prismllm.channel.infra.quota

import dev.soven.prismllm.spi.quota.ChannelQuotaProvider
import org.slf4j.LoggerFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceConfigurationError
import java.util.ServiceLoader

/**
 * 从内置提供者和可选插件目录发现额度查询提供者。
 *
 * 外部 JAR 使用父优先的类加载器，只能实现当前网关暴露的 SPI；同一 ID 只保留第一个
 * 成功加载的提供者，后续重复项会被忽略并记录日志。
 */
class ChannelQuotaProviderRegistry(
    pluginDirectory: Path? = null,
    parentClassLoader: ClassLoader = ChannelQuotaProviderRegistry::class.java.classLoader,
    builtIns: Iterable<ChannelQuotaProvider> = emptyList(),
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(ChannelQuotaProviderRegistry::class.java)
    private val pluginClassLoader: URLClassLoader?
    private val providersById: LinkedHashMap<String, ChannelQuotaProvider> = LinkedHashMap()
    private val metadataById = LinkedHashMap<String, ChannelQuotaProviderInfo>()
    private val failures = mutableListOf<ChannelQuotaPluginFailure>()

    init {
        builtIns.forEach { register(it, "built-in") }
        val jars = pluginDirectory?.let(::pluginJars).orEmpty()
        pluginClassLoader = if (jars.isEmpty()) {
            null
        } else {
            URLClassLoader(jars.map { it.toUri().toURL() }.toTypedArray(), parentClassLoader).also { loader ->
                load(loader, pluginDirectory!!.toAbsolutePath().toString())
            }
        }
        if (jars.isEmpty() && pluginDirectory != null && Files.exists(pluginDirectory)) {
            logger.info("no quota plugin JARs found in {}", pluginDirectory.toAbsolutePath())
        }
    }

    /** 已成功加载、按发现顺序排列的提供者。 */
    val providers: List<ChannelQuotaProvider>
        get() = providersById.values.toList()

    /** 元数据只在注册时读取一次，查询列表不会再次调用插件代码。 */
    val providerInfos: List<ChannelQuotaProviderInfo>
        get() = metadataById.values.toList()

    /** 加载失败不会阻止网关启动；管理面可在后续阶段展示这些诊断信息。 */
    val loadFailures: List<ChannelQuotaPluginFailure>
        get() = failures.toList()

    fun find(id: String): ChannelQuotaProvider? = providersById[id]

    private fun load(classLoader: ClassLoader, source: String) {
        val serviceLoader = ServiceLoader.load(ChannelQuotaProvider::class.java, classLoader)
        val iterator = serviceLoader.iterator()
        while (true) {
            try {
                if (!iterator.hasNext()) break
            } catch (cause: ServiceConfigurationError) {
                recordFailure(source, "插件服务声明无效", cause)
                continue
            }
            val provider = try {
                iterator.next()
            } catch (cause: ServiceConfigurationError) {
                recordFailure(source, cause.message ?: cause.javaClass.simpleName, cause)
                continue
            } catch (cause: LinkageError) {
                recordFailure(source, cause.message ?: cause.javaClass.simpleName, cause)
                continue
            }
            register(provider, source)
        }
    }

    private fun register(provider: ChannelQuotaProvider, source: String) {
        val metadata = runCatching {
            Triple(provider.id, provider.apiVersion, provider.displayName)
        }.getOrElse {
            recordFailure(source, "读取插件 ID 失败", it)
            closeRejected(provider)
            return
        }
        val id = metadata.first
        if (!Regex(ChannelQuotaProvider.ID_PATTERN).matches(id)) {
            recordFailure(source, "插件 ID 不合法: $id", null)
            closeRejected(provider)
            return
        }
        if (metadata.second != ChannelQuotaProvider.API_VERSION) {
            recordFailure(source, "插件 $id 使用不支持的 SPI 版本 " + metadata.second, null)
            closeRejected(provider)
            return
        }
        if (metadata.third.isBlank() || metadata.third.length > 120 || metadata.third.any { it.isISOControl() }) {
            recordFailure(source, "插件 $id 的展示名称无效", null)
            closeRejected(provider)
            return
        }
        if (providersById.putIfAbsent(id, provider) != null) {
            recordFailure(source, "重复的插件 ID: $id", null)
            closeRejected(provider)
            return
        }
        metadataById[id] = ChannelQuotaProviderInfo(id, metadata.third, metadata.second)
        logger.info("loaded channel quota provider id={} name={} source={}", id, metadata.third, source)
    }

    private fun closeRejected(provider: ChannelQuotaProvider) {
        if (providersById.values.none { it === provider }) runCatching { provider.close() }
    }

    private fun recordFailure(source: String, message: String, cause: Throwable?) {
        failures += ChannelQuotaPluginFailure(source, message)
        if (cause == null) logger.warn("unable to load channel quota provider: source={} message={}", source, message)
        else logger.warn("unable to load channel quota provider: source={} message={}", source, message, cause)
    }

    private fun pluginJars(directory: Path): List<Path> {
        if (!Files.exists(directory)) return emptyList()
        if (!Files.isDirectory(directory)) {
            recordFailure(directory.toAbsolutePath().toString(), "插件路径不是目录", null)
            return emptyList()
        }
        return try { Files.list(directory).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().lowercase().endsWith(".jar") }
                .sorted()
                .toList()
        } } catch (cause: Exception) {
            recordFailure(directory.fileName.toString(), "无法读取插件目录", cause)
            emptyList()
        }
    }

    override fun close() {
        providersById.values.forEach { provider ->
            runCatching { provider.close() }.onFailure { logger.warn("failed to close quota provider") }
        }
        runCatching { pluginClassLoader?.close() }
            .onFailure { logger.warn("failed to close channel quota plugin classloader", it) }
    }
}

data class ChannelQuotaPluginFailure(
    val source: String,
    val message: String,
)

data class ChannelQuotaProviderInfo(val id: String, val displayName: String, val apiVersion: Int)
