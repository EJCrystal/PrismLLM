package dev.soven.prismllm.testsupport.db

import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * 整个测试 JVM 惰性共享一个 PostgreSQL 容器与已迁移的 `prism_template` 模板库。
 * 每个测试类克隆独立数据库，以便并行执行而不共享测试数据。
 */
object SharedPostgres {
    private const val TEMPLATE_DB = "prism_template"
    private val counter = AtomicInteger()
    private val SAFE_DATABASE_NAME = Regex("[A-Za-z0-9_]+")

    val container: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer("postgres:16-alpine").apply {
            withDatabaseName("postgres")
            start()
        }.also { createTemplate(it) }
    }

    /** 为当前测试类克隆一份模板库，返回它的 JDBC URL。 */
    fun newDatabase(): String {
        val target = container
        // 数据库名只由内部计数器生成，不接收外部输入；DDL 无法使用绑定参数，
        // 因此仍校验字符集并转义标识符，避免将来改动命名逻辑时引入注入。
        val name = "prism_test_" + counter.incrementAndGet()
        execute(target, "create database ${quoteIdentifier(name)} template ${quoteIdentifier(TEMPLATE_DB)}")
        return jdbcUrl(target, name)
    }

    private fun createTemplate(target: PostgreSQLContainer<*>) {
        execute(target, "create database ${quoteIdentifier(TEMPLATE_DB)}")
        Flyway.configure()
            .dataSource(jdbcUrl(target, TEMPLATE_DB), target.username, target.password)
            .locations("classpath:db/migration")
            .failOnMissingLocations(true)
            .load()
            .migrate()
    }

    private fun quoteIdentifier(name: String): String {
        require(name.matches(SAFE_DATABASE_NAME)) { "unsafe database name: $name" }
        return "\"$name\""
    }

    private fun execute(target: PostgreSQLContainer<*>, sql: String) {
        DriverManager.getConnection(jdbcUrl(target, "postgres"), target.username, target.password).use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }

    private fun jdbcUrl(target: PostgreSQLContainer<*>, database: String): String =
        target.jdbcUrl.substringBefore("?").substringBeforeLast("/") + "/" + database
}
