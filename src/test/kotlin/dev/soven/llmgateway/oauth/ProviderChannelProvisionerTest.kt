package dev.soven.llmgateway.oauth

import dev.soven.llmgateway.config.GatewayConfig
import dev.soven.llmgateway.db.DatabaseFactory
import dev.soven.llmgateway.domain.ChannelKind
import dev.soven.llmgateway.domain.ChannelModelRecord
import dev.soven.llmgateway.domain.OAuthProvider
import dev.soven.llmgateway.domain.ProviderEndpoints
import dev.soven.llmgateway.repo.ChannelRepository
import dev.soven.llmgateway.repo.NewChannelKey
import dev.soven.llmgateway.repo.NewOAuthAccount
import dev.soven.llmgateway.repo.OAuthAccountRepository
import dev.soven.llmgateway.security.CredentialCipher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 订阅账号只服务官方端点：provider 渠道由系统置备，账号导入后自动收敛过去。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProviderChannelProvisionerTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var factory: DatabaseFactory
    private lateinit var channels: ChannelRepository
    private lateinit var accounts: OAuthAccountRepository
    private lateinit var provisioner: ProviderChannelProvisioner
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
                    "GATEWAY_MASTER_KEY" to Base64.getEncoder().encodeToString(ByteArray(32) { 9 }),
                    "ADMIN_PASSWORD" to "test-password",
                ),
            ),
        )
        factory.migrate()
        cipher = CredentialCipher(ByteArray(32) { 9 })
        channels = ChannelRepository(factory.database, cipher)
        accounts = OAuthAccountRepository(factory.database)
        provisioner = ProviderChannelProvisioner(channels, accounts)
    }

    @AfterAll
    fun stop() {
        if (::factory.isInitialized) factory.close()
        if (::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `provisions the official endpoint channel once`() = runBlocking {
        val first = provisioner.ensure(OAuthProvider.CHATGPT)
        val second = provisioner.ensure(OAuthProvider.CHATGPT)

        assertEquals(first, second)
        val channel = channels.find(first!!)!!
        assertEquals(ChannelKind.PROVIDER, channel.kind)
        assertEquals(OAuthProvider.CHATGPT, channel.provider)
        assertEquals(ProviderEndpoints.chatGpt.baseUrl, channel.baseUrl)
        assertEquals(ProviderEndpoints.chatGpt.protocols, channel.protocols)
        assertTrue(channel.models.isNotEmpty())
    }

    @Test
    fun `provider channel models can be replaced`() = runBlocking {
        val channelId = provisioner.ensure(OAuthProvider.CHATGPT)!!
        assertTrue(channels.replaceModels(channelId, listOf(ChannelModelRecord("custom-model"))))
        assertEquals(listOf("custom-model"), channels.find(channelId)!!.models)
    }

    @Test
    fun `antigravity is provisioned through code assist`() = runBlocking {
        val channelId = provisioner.ensure(OAuthProvider.ANTIGRAVITY)
        assertNotNull(channelId)
        val channel = channels.find(channelId)!!
        assertEquals(ChannelKind.PROVIDER, channel.kind)
        assertEquals("https://daily-cloudcode-pa.googleapis.com", channel.baseUrl)
    }

    @Test
    fun `reconcile moves existing accounts onto the provider channel`() = runBlocking {
        val manual = channels.create(
            name = "manual-" + UUID.randomUUID(),
            baseUrl = "https://example.test/v1",
            keys = listOf(NewChannelKey("key", cipher.encrypt("token"))),
            authType = "bearer",
            enabled = true,
        )
        val account = accounts.create(
            NewOAuthAccount(
                name = "provisioned",
                provider = OAuthProvider.CHATGPT,
                accountEmail = "provisioned-${UUID.randomUUID()}@example.com",
                refreshToken = cipher.encrypt("refresh-token"),
                channelIds = listOf(manual.id),
            ),
        )
        assertEquals(setOf(manual.id), account.channelIds)

        provisioner.reconcile()

        val providerChannel = channels.findProviderChannel(OAuthProvider.CHATGPT)!!
        assertEquals(setOf(providerChannel.id), accounts.findDetailed(account.id)!!.channelIds)
        // 幂等：再跑一次不会新建渠道，也不会改动已就位的账号。
        assertEquals(0, provisioner.reconcile())
    }
}
