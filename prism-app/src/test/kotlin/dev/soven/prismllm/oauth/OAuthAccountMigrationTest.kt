package dev.soven.prismllm.oauth

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * V52 迁移回归：旧 OAuth 密钥必须完整搬到独立账号，并从 channel_keys 中删除。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OAuthAccountMigrationTest {
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
    fun `migrates oauth keys into independent accounts and removes the keys`() {
        flyway("51").migrate()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    insert into channels (id, name, base_url, auth_type, protocols, enabled, priority, created_at, updated_at)
                    values ('ch-1', 'legacy-channel', 'https://chatgpt.example/v1', 'bearer', 'chat_completions', true, 1, 1000, 1000)
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    insert into channel_keys (id, channel_id, name, credential_ciphertext, credential_nonce, credential_key_version,
                        state, state_version, priority, created_at, updated_at)
                    values ('key-1', 'ch-1', 'legacy-account', 'refresh-cipher', 'refresh-nonce', 2, 'ACTIVE', 3, 7, 1000, 2000)
                    """.trimIndent(),
                )
                statement.execute(
                    "insert into channel_key_models (key_id, upstream_model, created_at) values ('key-1', 'gpt-5', 1000)",
                )
                statement.execute(
                    """
                    insert into channel_key_oauth_accounts (key_id, provider, account_email, account_plan, access_token_ciphertext,
                        access_token_nonce, access_token_key_version, access_token_expires_at, token_type, scope, auth_state,
                        refresh_failure_count, last_refresh_error, last_refreshed_at, proxy_url, window_limit, window_seconds,
                        max_concurrent_requests, chatgpt_account_id, quota_primary_used_percent, quota_primary_window_seconds,
                        quota_primary_resets_at, quota_secondary_used_percent, quota_secondary_window_seconds,
                        quota_secondary_resets_at, quota_allowed, quota_credits_balance, quota_credits_unlimited,
                        quota_reset_credits, quota_checked_at, quota_error, created_at, updated_at)
                    values ('key-1', 'chatgpt', 'free@example.com', 'free', 'access-cipher', 'access-nonce', 2, 3000, 'Bearer',
                        'scope-a', 'ACTIVE', 0, null, 1500, 'http://127.0.0.1:7890', 40, 10800, 2, 'acc-1',
                        25.0, 18000, 4000, 10.0, 604800, 5000, true, 3.5, false, 2, 2000, null, 1000, 2000)
                    """.trimIndent(),
                )
                statement.execute(
                    "insert into channel_key_oauth_windows (key_id, hits, cooldown_until, consecutive_rate_limits, updated_at) values ('key-1', '[1000,2000]', 3000, 1, 2500)",
                )
            }
        }

        flyway("latest").migrate()

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select * from oauth_accounts where id = 'key-1'").use { rows ->
                    assertEquals(true, rows.next())
                    assertEquals("free@example.com", rows.getString("account_email"))
                    assertEquals("refresh-cipher", rows.getString("refresh_token_ciphertext"))
                    assertEquals(2, rows.getInt("refresh_token_key_version"))
                    assertEquals("access-cipher", rows.getString("access_token_ciphertext"))
                    assertEquals(7, rows.getInt("priority"))
                    assertEquals(40, rows.getInt("window_limit"))
                    assertEquals("acc-1", rows.getString("chatgpt_account_id"))
                    // V53 用成功读数时间回填首次尝试时间，保持迁移前的刷新节奏。
                    assertEquals(2000, rows.getLong("quota_attempted_at"))
                }
                statement.executeQuery("select channel_id from oauth_account_channels where account_id = 'key-1'").use { rows ->
                    assertEquals(true, rows.next())
                    assertEquals("ch-1", rows.getString("channel_id"))
                }
                statement.executeQuery("select upstream_model from oauth_account_models where account_id = 'key-1'").use { rows ->
                    assertEquals(true, rows.next())
                    assertEquals("gpt-5", rows.getString("upstream_model"))
                }
                statement.executeQuery("select hits, cooldown_until from oauth_account_windows where account_id = 'key-1'").use { rows ->
                    assertEquals(true, rows.next())
                    assertEquals("[1000,2000]", rows.getString("hits"))
                    assertEquals(3000, rows.getLong("cooldown_until"))
                }
                statement.executeQuery("select count(*) from channel_keys where id = 'key-1'").use { rows ->
                    assertEquals(true, rows.next())
                    assertEquals(0, rows.getInt(1))
                }
                statement.executeQuery("select count(*) from information_schema.tables where table_name = 'channel_key_oauth_accounts'").use { rows ->
                    assertEquals(true, rows.next())
                    assertEquals(0, rows.getInt(1))
                }
            }
        }
    }

    private fun flyway(target: String): Flyway = Flyway.configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .target(if (target == "latest") MigrationVersion.LATEST else MigrationVersion.fromVersion(target))
        .load()
}
