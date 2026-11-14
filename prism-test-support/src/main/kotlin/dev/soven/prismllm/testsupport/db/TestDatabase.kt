package dev.soven.prismllm.testsupport.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database

/** 测试自有库的连接池；模板克隆已经迁移，此处不再运行 Flyway。 */
class TestDatabase(jdbcUrl: String, username: String, password: String) : AutoCloseable {
    private val dataSource = HikariDataSource(
        HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = username
            this.password = password
            maximumPoolSize = 10
            minimumIdle = 2
            connectionTimeout = 5_000
            validationTimeout = 3_000
        },
    )

    val database: Database = Database.connect(dataSource)

    override fun close() {
        dataSource.close()
    }
}
