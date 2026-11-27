package dev.soven.prismllm.antigravity

import dev.soven.prismllm.admin.domain.AntigravityAgySettingsRecord
import dev.soven.prismllm.admin.persistence.AntigravityAgySettingsRepository
import dev.soven.prismllm.config.AntigravityAgyConfig
import dev.soven.prismllm.shared.error.badRequest
import dev.soven.prismllm.shared.error.GatewayError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
internal data class AntigravityAgySettingsSnapshot(
    val enabled: Boolean,
    val proxy: String,
    val status: String,
    val missingDependencies: List<String> = emptyList(),
    val error: String? = null,
    val updatedAt: Long = 0,
)

internal class AntigravityAgyRuntime(
    private val defaults: AntigravityAgyConfig,
    private val repository: AntigravityAgySettingsRepository,
    private val onAuthenticated: suspend (AntigravityAgyAccount) -> Unit,
    private val prepare: suspend (AntigravityAgyConfig, (String, List<String>) -> Unit, () -> Boolean) -> Unit =
        AntigravityAgyEnvironment()::prepare,
) : AutoCloseable {
    private data class Services(val login: AntigravityAgyLoginService, val gateway: AntigravityAgyGateway) : AutoCloseable {
        override fun close() {
            login.close()
            gateway.close()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val changes = Mutex()
    private val monitor = Any()
    private var preparation: Job? = null
    private var phase = "CHECKING"

    @Volatile
    private var preparing = false

    @Volatile
    private var current = AntigravityAgySettingsSnapshot(false, defaults.proxy, "DISABLED")

    @Volatile
    private var services: Services? = null

    val login: AntigravityAgyLoginService? get() = if (current.enabled && current.status == "READY") services?.login else null
    val gateway: AntigravityAgyGateway? get() = if (current.enabled && current.status == "READY") services?.gateway else null
    fun snapshot(): AntigravityAgySettingsSnapshot = current

    suspend fun start() {
        val saved = repository.load()
        if (saved != null) {
            synchronized(monitor) { current = current.copy(enabled = saved.enabled, proxy = saved.proxy, updatedAt = saved.updatedAt) }
            if (saved.enabled) launchPreparation()
        }
    }

    suspend fun update(enabled: Boolean, proxy: String): AntigravityAgySettingsSnapshot = changes.withLock {
        try {
            defaults.copy(proxy = proxy)
        } catch (failure: IllegalArgumentException) {
            throw badRequest("invalid_agy_proxy", "agy 代理必须是 http://127.0.0.1:端口，不接受账号密码、路径或非回环主机")
        }
        if (preparing && (proxy != current.proxy || enabled && !current.enabled)) {
            throw GatewayError(HttpStatusCode.Conflict, "agy_preparation_busy", "运行环境正在准备或停止，请等待完成后再修改代理或重新开启", "invalid_request_error")
        }
        withContext(NonCancellable) {
            val saved = repository.save(AntigravityAgySettingsRecord(enabled, proxy))
            val unchanged = enabled && current.enabled && current.proxy == proxy && current.status == "READY"
            synchronized(monitor) {
                current = current.copy(enabled = enabled, proxy = proxy, updatedAt = saved.updatedAt, error = null)
            }
            if (!unchanged) {
                val previous = services
                services = null
                previous?.close()
                if (preparing) {
                    synchronized(monitor) { current = current.copy(status = if (enabled) phase else "STOPPING") }
                } else if (enabled) {
                    launchPreparation()
                } else {
                    synchronized(monitor) { current = current.copy(status = "DISABLED", missingDependencies = emptyList()) }
                }
            }
            current
        }
    }

    private fun launchPreparation() {
        synchronized(monitor) {
            preparing = true
            phase = "CHECKING"
            current = current.copy(status = phase, error = null, missingDependencies = emptyList())
        }
        preparation = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val config = defaults.copy(proxy = current.proxy)
                prepare(config, { status, missing ->
                    synchronized(monitor) {
                        phase = status
                        current = current.copy(status = if (current.enabled) status else "STOPPING", missingDependencies = missing)
                    }
                }, { current.enabled })
                changes.withLock {
                    synchronized(monitor) {
                        preparing = false
                        if (current.enabled) {
                            services = newServices(config)
                            current = current.copy(status = "READY", error = null)
                        } else {
                            current = current.copy(status = "DISABLED", missingDependencies = emptyList())
                        }
                    }
                }
            } catch (failure: CancellationException) {
                preparing = false
                throw failure
            } catch (failure: Exception) {
                changes.withLock {
                    synchronized(monitor) {
                        preparing = false
                        current = current.copy(status = if (current.enabled) "FAILED" else "DISABLED",
                            error = if (current.enabled) (failure as? AgyPreparationException)?.message ?: "运行环境准备失败，请检查服务端安装权限、代理和磁盘空间后重试" else null)
                    }
                }
            }
        }.also { it.start() }
    }

    private fun newServices(config: AntigravityAgyConfig) = Services(
        AntigravityAgyLoginService(config, onAuthenticated = onAuthenticated), AntigravityAgyGateway(config),
    )

    override fun close() {
        scope.cancel()
        runBlocking { preparation?.cancelAndJoin() }
        services?.close()
        services = null
    }
}
