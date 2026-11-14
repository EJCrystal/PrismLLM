package dev.soven.prismllm.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.soven.prismllm.config.GatewayConfig
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
            poolName = "PrismLLM"
        },
    )

    val database: Database = Database.connect(dataSource)

    /** 执行迁移并返回本次真正应用的版本，供启动期的一次性数据搬迁判断。 */
    fun migrate(): Set<String> {
        val result = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .failOnMissingLocations(true)
            .load()
            .migrate()
        return result.migrations.mapNotNull { it.version }.toSet()
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
