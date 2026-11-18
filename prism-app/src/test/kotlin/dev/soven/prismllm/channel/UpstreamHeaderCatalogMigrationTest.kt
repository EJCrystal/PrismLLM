package dev.soven.prismllm.channel

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V77 迁移只修正早期内置的 Antigravity 2.32.1 旧默认 UA；
 * 自定义 headers、名称、来源、其他版本与固定版本必须原样保留。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("component")
class UpstreamHeaderCatalogMigrationTest {
    private lateinit var postgres: PostgreSQLContainer<*>

    @BeforeAll
    fun start() {
        postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }
    }

    @AfterAll
    fun stop() {
        if (::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `repairs only the exact legacy Antigravity user agent`() {
        withDatabaseAt76 { connection ->
            seed(
                connection,
                profile("antigravity", "2.32.1", "Antigravity", LEGACY_HEADERS, "antigravity:hub", 1234),
                profile("antigravity", "2.32.0", "Antigravity", LEGACY_HEADERS, "antigravity:hub", 1000),
                profile("other", "2.32.1", "Other", LEGACY_HEADERS, "other:source", 4000),
            )
            pin(connection, "antigravity", "2.32.1")
            catalogState(connection)

            flyway("latest").migrate()

            assertEquals(REPAIRED_HEADERS, headers(connection, "antigravity", "2.32.1", "Antigravity", "antigravity:hub"))
            assertEquals(1234L, fetchedAt(connection, "antigravity", "2.32.1", "Antigravity", "antigravity:hub"))
            // Pin 和目录状态不参与修复，不能被迁移删除或刷新。
            assertEquals(1L, count(connection, "select count(*) from upstream_header_profile_pins where profile_id = 'antigravity' and version = '2.32.1'"))
            assertEquals(1000L, count(connection, "select checked_at from upstream_header_catalog_state where id = 1"))
            assertEquals("keep", text(connection, "select last_error from upstream_header_catalog_state where id = 1"))
        }
    }

    @Test
    fun `leaves customized rows other versions and malformed json untouched`() {
        val customHeaders = """{"User-Agent":"antigravity/hub/2.32.1 custom"}"""
        withDatabaseAt76 { connection ->
            seed(connection, profile("antigravity", "2.32.1", "Antigravity", customHeaders, "antigravity:hub", 10))
            flyway("latest").migrate()
            assertEquals(customHeaders, headers(connection, "antigravity", "2.32.1", "Antigravity", "antigravity:hub"))
        }
        withDatabaseAt76 { connection ->
            seed(connection, profile("antigravity", "2.32.1", "Renamed", LEGACY_HEADERS, "antigravity:hub", 20))
            flyway("latest").migrate()
            assertEquals(LEGACY_HEADERS, headers(connection, "antigravity", "2.32.1", "Renamed", "antigravity:hub"))
        }
        withDatabaseAt76 { connection ->
            seed(connection, profile("antigravity", "2.32.1", "Antigravity", LEGACY_HEADERS, "custom:source", 30))
            flyway("latest").migrate()
            assertEquals(LEGACY_HEADERS, headers(connection, "antigravity", "2.32.1", "Antigravity", "custom:source"))
        }
        withDatabaseAt76 { connection ->
            seed(connection, profile("antigravity", "2.32.1", "Antigravity", "not-json", "antigravity:hub", 40))
            flyway("latest").migrate()
            assertEquals("not-json", headers(connection, "antigravity", "2.32.1", "Antigravity", "antigravity:hub"))
        }
        withDatabaseAt76 { connection ->
            seed(connection, profile("antigravity", "2.31.9", "Antigravity", LEGACY_HEADERS, "antigravity:hub", 50))
            flyway("latest").migrate()
            assertEquals(LEGACY_HEADERS, headers(connection, "antigravity", "2.31.9", "Antigravity", "antigravity:hub"))
        }
    }

    @Test
    fun `is idempotent and tolerates an empty catalog`() {
        withDatabaseAt76 { connection ->
            seed(connection, profile("antigravity", "2.32.1", "Antigravity", LEGACY_HEADERS, "antigravity:hub", 1))
            flyway("latest").migrate()
            assertEquals(REPAIRED_HEADERS, headers(connection, "antigravity", "2.32.1", "Antigravity", "antigravity:hub"))

            // 迁移 SQL 自身再次执行时不再命中旧默认值。
            connection.createStatement().use { statement ->
                assertEquals(0, statement.executeUpdate(REPAIR_SQL))
            }
            assertEquals(REPAIRED_HEADERS, headers(connection, "antigravity", "2.32.1", "Antigravity", "antigravity:hub"))
        }
        withDatabaseAt76 { connection ->
            flyway("latest").migrate()
            assertEquals(0L, count(connection, "select count(*) from upstream_header_profile_versions"))
        }
    }

    private fun withDatabaseAt76(block: (Connection) -> Unit) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("drop schema if exists public cascade")
                statement.execute("create schema public")
            }
            flyway("76").migrate()
            block(connection)
        }
    }

    private fun flyway(target: String): Flyway = Flyway.configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .target(if (target == "latest") MigrationVersion.LATEST else MigrationVersion.fromVersion(target))
        .load()

    private data class Profile(
        val id: String,
        val version: String,
        val name: String,
        val headers: String,
        val source: String,
        val fetchedAt: Long,
    )

    private fun profile(
        id: String,
        version: String,
        name: String,
        headers: String,
        source: String,
        fetchedAt: Long,
    ) = Profile(id, version, name, headers, source, fetchedAt)

    private fun seed(connection: Connection, vararg rows: Profile) {
        connection.prepareStatement(
            "insert into upstream_header_profile_versions (profile_id, version, name, headers, source, fetched_at) values (?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            rows.forEach { row ->
                statement.setString(1, row.id)
                statement.setString(2, row.version)
                statement.setString(3, row.name)
                statement.setString(4, row.headers)
                statement.setString(5, row.source)
                statement.setLong(6, row.fetchedAt)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun pin(connection: Connection, profileId: String, version: String) {
        connection.prepareStatement(
            "insert into upstream_header_profile_pins (profile_id, version, pinned_at) values (?, ?, ?)",
        ).use { statement ->
            statement.setString(1, profileId)
            statement.setString(2, version)
            statement.setLong(3, 999)
            statement.executeUpdate()
        }
    }

    private fun catalogState(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                "insert into upstream_header_catalog_state (id, checked_at, success_at, last_error) values (1, 1000, 900, 'keep')",
            )
        }
    }

    private fun headers(
        connection: Connection,
        profileId: String,
        version: String,
        name: String,
        source: String,
    ): String = queryOne(
        connection,
        "select headers from upstream_header_profile_versions where profile_id = ? and version = ? and name = ? and source = ?",
        listOf(profileId, version, name, source),
    ) { rows -> rows.getString(1) }

    private fun fetchedAt(connection: Connection, profileId: String, version: String, name: String, source: String): Long =
        queryOne(
            connection,
            "select fetched_at from upstream_header_profile_versions where profile_id = ? and version = ? and name = ? and source = ?",
            listOf(profileId, version, name, source),
        ) { rows -> rows.getLong(1) }

    private fun count(connection: Connection, sql: String): Long =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                assertTrue(rows.next())
                rows.getLong(1)
            }
        }

    private fun text(connection: Connection, sql: String): String =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                assertTrue(rows.next())
                rows.getString(1)
            }
        }

    private fun <T> queryOne(connection: Connection, sql: String, values: List<Any>, read: (java.sql.ResultSet) -> T): T =
        connection.prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value ->
                when (value) {
                    is String -> statement.setString(index + 1, value)
                    is Long -> statement.setLong(index + 1, value)
                    else -> error("Unsupported bound value")
                }
            }
            statement.executeQuery().use { rows ->
                assertTrue(rows.next())
                read(rows)
            }
        }

    private companion object {
        const val LEGACY_HEADERS = """{"User-Agent":"antigravity/hub/2.32.1 windows/amd64"}"""
        const val REPAIRED_HEADERS =
            """{"User-Agent":"antigravity/hub/2.32.1 (aidev_client; os_type=linux; arch=amd64; cl=1021296516)"}"""
        const val REPAIR_SQL = """
            update upstream_header_profile_versions
            set headers = '$REPAIRED_HEADERS'
            where profile_id = 'antigravity'
              and version = '2.32.1'
              and name = 'Antigravity'
              and source = 'antigravity:hub'
              and headers = '$LEGACY_HEADERS'
        """
    }
}
