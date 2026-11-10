package dev.soven.llmgateway.repo

import dev.soven.llmgateway.config.GatewayConfig
import dev.soven.llmgateway.db.DatabaseFactory
import dev.soven.llmgateway.oauth.persistence.NewOAuthAccount
import dev.soven.llmgateway.oauth.persistence.OAuthAccountRepository
import dev.soven.llmgateway.oauth.persistence.OAuthAccounts
import dev.soven.llmgateway.oauth.domain.OAuthAccountRecord
import dev.soven.llmgateway.shared.domain.OAuthProvider
import dev.soven.llmgateway.shared.crypto.CredentialCipher
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 管理面读取模型目录时只挑可用的账号：状态条件必须在数据库里生效，
 * 否则前面的不可用账号会把后面的正常账号挤出 limit。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OAuthAccountRepositoryTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var factory: DatabaseFactory
    private lateinit var repository: OAuthAccountRepository
    private lateinit var cipher: CredentialCipher

    @BeforeAll
    fun start() {
        postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }
        factory = DatabaseFactory(
            GatewayConfig.fromEnv(
                mapOf(
                    "DATABASE_URL" to postgres.jdbcUrl,
                    "DATABASE_USER" to postgres.username,
                    "DATABASE_PASSWORD" to postgres.password,
                    "GATEWAY_MASTER_KEY" to Base64.getEncoder().encodeToString(ByteArray(32) { 5 }),
                    "ADMIN_PASSWORD" to "test-password",
                ),
            ),
        )
        factory.migrate()
        cipher = CredentialCipher(ByteArray(32) { 5 })
        repository = OAuthAccountRepository(factory.database, OAuthProviderChannelLookup())
    }

    @AfterAll
    fun stop() {
        if (::factory.isInitialized) factory.close()
        if (::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `usable accounts skip reauth and disabled before the limit applies`() = runBlocking {
        val healthy = create("healthy", enabled = true)
        val reauth = create("reauth", enabled = true)
        val disabled = create("disabled", enabled = false)
        repository.recordFailure(reauth.id, "invalid_grant", terminal = true)
        // 账号按 updatedAt 倒序取，这里让不可用账号排在健康账号前面，
        // 复现「先 limit 再过滤」时健康账号被挤出结果的情况。
        val base = healthy.updatedAt
        reauth.touch(base + 2_000)
        disabled.touch(base + 1_000)
        val chatgpt = repository.create(
            NewOAuthAccount(
                name = "chatgpt",
                provider = OAuthProvider.CHATGPT,
                accountEmail = "chatgpt-" + UUID.randomUUID() + "@example.com",
                refreshToken = cipher.encrypt("refresh-token"),
            ),
        )

        val usable = repository.listUsableForProvider(OAuthProvider.ANTIGRAVITY, limit = 1)

        assertEquals(listOf(healthy.id), usable.map { it.id })
        // 其他 provider 的账号不会混进来。
        assertEquals(listOf(chatgpt.id), repository.listUsableForProvider(OAuthProvider.CHATGPT).map { it.id })
    }

    @Test
    fun `upstream context clears the stored tier when the upstream omits it`() = runBlocking {
        val account = create("context", enabled = true)

        repository.persistUpstreamContext(account.id, "proj-1", "standard-tier", 1_000)
        assertEquals(
            OAuthAccountRepository.UpstreamContext("proj-1", "standard-tier", 1_000),
            repository.upstreamContext(account.id),
        )
        // 上游这次没给 tier：数据库里不能留着上一次的值，否则重启后会被当成新鲜缓存。
        repository.persistUpstreamContext(account.id, "proj-1", null, 2_000)
        assertEquals(
            OAuthAccountRepository.UpstreamContext("proj-1", null, 2_000),
            repository.upstreamContext(account.id),
        )
    }

    private suspend fun create(name: String, enabled: Boolean): OAuthAccountRecord =
        repository.create(
            NewOAuthAccount(
                name = "$name-" + UUID.randomUUID(),
                provider = OAuthProvider.ANTIGRAVITY,
                accountEmail = name + "-" + UUID.randomUUID() + "@example.com",
                refreshToken = cipher.encrypt("refresh-token"),
                enabled = enabled,
            ),
        )

    private suspend fun OAuthAccountRecord.touch(updatedAt: Long) {
        suspendTransaction(factory.database) {
            OAuthAccounts.update({ OAuthAccounts.id eq id }) { it[OAuthAccounts.updatedAt] = updatedAt }
        }
    }
}
