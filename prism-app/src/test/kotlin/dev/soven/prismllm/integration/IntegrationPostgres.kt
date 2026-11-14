package dev.soven.prismllm.integration

import dev.soven.prismllm.testsupport.db.SharedPostgres
import org.testcontainers.containers.PostgreSQLContainer

/** 保留应用集成测试入口；容器与模板克隆统一由测试支持模块持有。 */
object IntegrationPostgres {
    val container: PostgreSQLContainer<*>
        get() = SharedPostgres.container

    fun newDatabase(): String = SharedPostgres.newDatabase()
}
