package dev.soven.prismllm.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * 健康检查与 Prometheus 指标端点。
 *
 * @author 文杰
 * @since 2026-10-21
 */
fun Route.healthRoutes(app: GatewayApplication) {
    get("/health/live") {
        call.respondText("ok")
    }
    get("/health/ready") {
        if (app.shuttingDown.get()) {
            app.metrics.databaseReady(false)
            call.respondText("shutting down", status = HttpStatusCode.ServiceUnavailable)
            return@get
        }
        if (app.databaseFactory.ping()) {
            app.metrics.databaseReady(true)
            call.respondText("ok")
        } else {
            app.metrics.databaseReady(false)
            call.respondText("database unavailable", status = HttpStatusCode.ServiceUnavailable)
        }
    }
    get("/metrics") {
        call.respondText(app.metrics.scrape(), ContentType.parse("text/plain; version=0.0.4"))
    }
}
