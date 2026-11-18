package dev.soven.prismllm.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.soven.prismllm.config.GatewayConfig
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.flywaydb.core.api.callback.Callback
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event
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

    val database: Database = Database.connect(dataSource, manager = { DiagnosticPreparationDeadline.transactionManager(it) })

    /** Register one migration-scoped prerequisite, committed before its SQL script is run. */
    fun beforeMigration(version: String, action: () -> Unit) {
        require(MigrationVersion.fromVersion(version) >= MigrationVersion.fromVersion("1"))
        migrationCallbacks += MigrationPreparationCallback(MigrationVersion.fromVersion(version), action)
    }

    private val migrationCallbacks = java.util.concurrent.CopyOnWriteArrayList<Callback>()

    /** Executed only immediately before an unapplied migration with the requested version. */
    private class MigrationPreparationCallback(
        private val version: MigrationVersion,
        private val action: () -> Unit,
    ) : Callback {
        override fun supports(event: Event, context: Context?): Boolean =
            event == Event.BEFORE_EACH_MIGRATE && context?.migrationInfo?.version == version

        override fun canHandleInTransaction(event: Event, context: Context): Boolean = false

        override fun handle(event: Event, context: Context) {
            // Use another connection/transaction. The callback connection is not enlisted, so the
            // provisioning changes commit before V74 starts and never wait on Flyway history locks.
            action()
        }

        override fun getCallbackName(): String = "ModelCredentialRouteMigrationPreparation"
    }

    /** 执行迁移并返回本次真正应用的版本，供启动期的一次性数据搬迁判断。 */
    fun migrate(): Set<String> {
        val result = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .failOnMissingLocations(true)
            .callbacks(*migrationCallbacks.toTypedArray())
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
