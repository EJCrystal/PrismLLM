package dev.soven.prismllm

import dev.soven.prismllm.api.GatewayApplication
import dev.soven.prismllm.config.GatewayConfig
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 网关进程入口。SIGTERM 时先翻转 readiness，再按配置 drain。
 *
 * @author 文杰
 * @since 2026-10-21
 */
fun main() {
    // Ktor 自带 hook 会并行 stop，引擎 quiet period 也不会等待在途流；停机只由此处负责。
    System.setProperty("io.ktor.server.engine.ShutdownHook", "false")
    val config = GatewayConfig.fromEnv()
    val app = GatewayApplication(config)
    val server = embeddedServer(Netty, configure = {
        connector {
            port = config.port
            host = "0.0.0.0"
        }
        // TLS/HTTP2 由入口反向代理处理；下游末帧跟踪覆盖此处的 HTTP/1 管道。
        enableHttp2 = false
        enableH2c = false
    }) {
        app.install(this)
    }
    val stopped = CountDownLatch(1)
    val stopping = AtomicBoolean(false)
    fun stop() {
        if (!stopping.compareAndSet(false, true)) return
        try {
            app.beginShutdown()
            runBlocking { app.drainRequests(config.drainTimeoutMs) }
        } finally {
            try {
                server.stop(0, 5_000)
            } finally {
                try {
                    app.close()
                } finally {
                    stopped.countDown()
                }
            }
        }
    }
    Runtime.getRuntime().addShutdownHook(Thread(::stop, "GatewayShutdownHook"))
    try {
        // wait=true 会在监听关闭后额外 stop(defaults)，不能与自定义 drain 并行。
        server.start(wait = false)
        stopped.await()
    } finally {
        stop()
    }
}
