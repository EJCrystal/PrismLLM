package dev.soven.llmgateway.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.soven.llmgateway.config.GatewayConfig
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * 数据库连接与迁移入口。启动时执行 Flyway，运行期只暴露 DataSource 与 Exposed Database。
 *
 * @author 文杰
 * @since 2026-10-21
 */
class DatabaseFactory(config: GatewayConfig) : AutoCloseable {
    private val dataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.databaseUrl
            username = config.databaseUser
            password = config.databasePassword
            maximumPoolSize = 10
            minimumIdle = 2
            connectionTimeout = 5_000
            validationTimeout = 3_000
            poolName = "llm-gateway"
        },
    )

    val database: Database = Database.connect(dataSource)

    fun migrate() {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    fun ping(): Boolean = runCatching {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("select 1") }
        }
    }.isSuccess

    override fun close() {
        dataSource.close()
    }
}
