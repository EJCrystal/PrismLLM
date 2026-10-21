package dev.soven.llmgateway

import dev.soven.llmgateway.api.GatewayApplication
import dev.soven.llmgateway.config.GatewayConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * 网关进程入口。SIGTERM 时先翻转 readiness，再按配置 drain。
 *
 * @author 文杰
 * @since 2026-10-21
 */
fun main() {
    val config = GatewayConfig.fromEnv()
    val app = GatewayApplication(config)
    val server = embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        app.install(this)
    }
    Runtime.getRuntime().addShutdownHook(
        Thread {
            app.beginShutdown()
            server.stop(config.drainTimeoutMs, config.drainTimeoutMs + 5_000)
        },
    )
    server.start(wait = true)
}
