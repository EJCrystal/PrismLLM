package dev.soven.llmgateway.oauth

import dev.soven.llmgateway.config.GatewayConfig
import dev.soven.llmgateway.db.DatabaseFactory
import dev.soven.llmgateway.channel.domain.ChannelKind
import dev.soven.llmgateway.channel.domain.ChannelModelRecord
import dev.soven.llmgateway.shared.domain.GatewayProtocol
import dev.soven.llmgateway.shared.domain.OAuthProvider
import dev.soven.llmgateway.channel.domain.ProviderEndpoints
import dev.soven.llmgateway.channel.persistence.ChannelRepository
import dev.soven.llmgateway.channel.persistence.NewChannelKey
import dev.soven.llmgateway.repo.NewOAuthAccount
import dev.soven.llmgateway.repo.OAuthAccountRepository
import dev.soven.llmgateway.shared.crypto.CredentialCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    fun `concurrent ensure calls create a single provider channel`() = runBlocking {
        // 清掉已有渠道，让并发调用真的同时进入「查不到就创建」。
        channels.findProviderChannel(OAuthProvider.CHATGPT)?.let { channels.delete(it.id) }

        val ids = (1..8)
            .map { async(Dispatchers.IO) { provisioner.ensure(OAuthProvider.CHATGPT) } }
            .awaitAll()

        assertEquals(1, ids.toSet().size)
        assertEquals(
            1,
            channels.list().count { it.kind == ChannelKind.PROVIDER && it.provider == OAuthProvider.CHATGPT },
        )
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
        assertEquals(listOf(GatewayProtocol.GEMINI), channel.protocols)
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
                // 等价于 V54 给迁移时已存在的账号打上的待搬迁标记。
                providerChannelPending = true,
            ),
        )
        assertEquals(setOf(manual.id), account.channelIds)

        assertEquals(1, provisioner.reconcile())

        val providerChannel = channels.findProviderChannel(OAuthProvider.CHATGPT)!!
        assertEquals(setOf(providerChannel.id), accounts.findDetailed(account.id)!!.channelIds)
        // 幂等：标记已清除，再跑一次不会新建渠道，也不会改动已就位的账号。
        assertEquals(0, provisioner.reconcile())
    }

    @Test
    fun `reconcile leaves accounts created after the migration alone`() = runBlocking {
        val manual = channels.create(
            name = "manual-" + UUID.randomUUID(),
            baseUrl = "https://example.test/v1",
            keys = listOf(NewChannelKey("key", cipher.encrypt("token"))),
            authType = "bearer",
            enabled = true,
        )
        val account = accounts.create(
            NewOAuthAccount(
                name = "post-migration",
                provider = OAuthProvider.CHATGPT,
                accountEmail = "post-migration-${UUID.randomUUID()}@example.com",
                refreshToken = cipher.encrypt("refresh-token"),
                channelIds = listOf(manual.id),
            ),
        )

        assertEquals(0, provisioner.reconcile())
        assertEquals(setOf(manual.id), accounts.findDetailed(account.id)!!.channelIds)
    }

    @Test
    fun `reconcile moves accounts of every servable provider`() = runBlocking {
        val antigravity = accounts.create(
            NewOAuthAccount(
                name = "pending-antigravity",
                provider = OAuthProvider.ANTIGRAVITY,
                accountEmail = "pending-antigravity-${UUID.randomUUID()}@example.com",
                refreshToken = cipher.encrypt("refresh-token"),
                providerChannelPending = true,
            ),
        )
        val chatgpt = accounts.create(
            NewOAuthAccount(
                name = "pending-chatgpt",
                provider = OAuthProvider.CHATGPT,
                accountEmail = "pending-chatgpt-${UUID.randomUUID()}@example.com",
                refreshToken = cipher.encrypt("refresh-token"),
                providerChannelPending = true,
            ),
        )

        // 两个 provider 现在都能置备，一次对账把两个账号各自挂到自己的系统渠道。
        assertEquals(2, provisioner.reconcile())

        val chatGptChannel = channels.findProviderChannel(OAuthProvider.CHATGPT)!!
        val antigravityChannel = channels.findProviderChannel(OAuthProvider.ANTIGRAVITY)!!
        assertEquals(setOf(chatGptChannel.id), accounts.findDetailed(chatgpt.id)!!.channelIds)
        assertEquals(setOf(antigravityChannel.id), accounts.findDetailed(antigravity.id)!!.channelIds)
        assertTrue(accounts.listPendingProviderChannel(OAuthProvider.entries).isEmpty())
    }
}
