package dev.soven.prismllm.oauth

import dev.soven.prismllm.testsupport.db.SharedPostgres
import dev.soven.prismllm.testsupport.db.TestDatabase
import dev.soven.prismllm.channel.persistence.ChannelModels
import dev.soven.prismllm.oauth.domain.OAuthAccountModelCatalogEntry
import dev.soven.prismllm.oauth.domain.OAuthAccountRecord
import dev.soven.prismllm.oauth.persistence.OAuthAccounts
import dev.soven.prismllm.oauth.persistence.OAuthAccountModels
import dev.soven.prismllm.oauth.persistence.OAuthAccountChannels
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import dev.soven.prismllm.channel.domain.ChannelKind
import dev.soven.prismllm.channel.domain.ChannelModelRecord
import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.shared.domain.OAuthProvider
import dev.soven.prismllm.channel.domain.ProviderEndpoints
import dev.soven.prismllm.channel.persistence.ChannelRepository
import dev.soven.prismllm.channel.persistence.NewChannelKey
import dev.soven.prismllm.oauth.persistence.NewOAuthAccount
import dev.soven.prismllm.oauth.persistence.OAuthAccountRepository
import dev.soven.prismllm.repo.OAuthProviderChannelLookup
import dev.soven.prismllm.shared.crypto.CredentialCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
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
@Tag("component")
class ProviderChannelProvisionerTest {
    private lateinit var database: TestDatabase
    private lateinit var channels: ChannelRepository
    private lateinit var accounts: OAuthAccountRepository
    private lateinit var provisioner: ProviderChannelProvisioner
    private lateinit var cipher: CredentialCipher

    @BeforeAll
    fun start() {
        database = TestDatabase(SharedPostgres.newDatabase(), SharedPostgres.container.username, SharedPostgres.container.password)
        cipher = CredentialCipher(ByteArray(32) { 9 })
        channels = ChannelRepository(database.database, cipher)
        accounts = OAuthAccountRepository(database.database, OAuthProviderChannelLookup())
        provisioner = ProviderChannelProvisioner(channels, accounts)
    }

    @AfterAll
    fun stop() {
        if (::database.isInitialized) database.close()
    }

    @BeforeEach
    fun clear() = runBlocking<Unit> {
        suspendTransaction(database.database) { exec("TRUNCATE oauth_accounts, channels CASCADE") }
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

        val other = ProviderChannelProvisioner(ChannelRepository(database.database, cipher), OAuthAccountRepository(database.database, OAuthProviderChannelLookup()))
        val ids = (1..8)
            .map { index -> async(Dispatchers.IO) { (if (index % 2 == 0) provisioner else other).ensure(OAuthProvider.CHATGPT) } }
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
        assertTrue(provisioner.setProviderModels(OAuthProvider.CHATGPT, listOf("custom-model")))
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

    @Test
    fun `same value manual freezes defaults upstream forces raw and auto immediately restores latest`() = runBlocking {
        val account = account()
        sync(account, "m" to "family")
        val initial = shared()
        val createdAt = modelCreatedAt("m")
        change(account, "m", "manual", "family")
        assertEquals(initial.revision + 1, shared().revision)
        assertEquals("manual", shared().mappings.single().mode)
        val frozen = shared().revision
        sync(account, "m" to "new-family")
        assertEquals(frozen + 1, shared().revision, "visible automatic default changes advance revision even when manual stays fixed")
        assertEquals("family", shared().mappings.single().mapping.canonicalModelId)
        assertEquals("new-family", shared().mappings.single().automaticCanonicalModelId)
        change(account, "m", "upstream")
        assertNull(shared().mappings.single().mapping.canonicalModelId)
        assertEquals("m", shared().mappings.single().mapping.publicModelId)
        assertEquals("manual", shared().mappings.single().mapping.mappingSource)
        assertEquals("upstream", shared().mappings.single().mode)
        change(account, "m", "auto")
        assertEquals("new-family", shared().mappings.single().mapping.canonicalModelId)
        assertNull(shared().mappings.single().mapping.mappingSource)
        assertEquals(createdAt, modelCreatedAt("m"))
        val stable = shared().revision
        change(account, "m", "auto")
        sync(account, "m" to "new-family")
        assertEquals(stable, shared().revision)
        assertEquals(emptyList(), accounts.findDetailed(account.id)!!.models, "mapping-only writes never change account authorization")
    }

    @Test
    fun `known empty union deletes auto and keeps dormant manual for reappearance`() = runBlocking {
        val account = account()
        sync(account, "manual" to "family", "auto" to null)
        change(account, "manual", "manual", "frozen")
        val createdAt = modelCreatedAt("manual")
        sync(account)
        val dormant = shared().mappings.single()
        assertEquals("manual", dormant.mapping.upstreamModel)
        assertFalse(dormant.mapping.catalogActive)
        assertEquals(emptyList(), providerChannel().models)
        assertEquals(emptySet(), channels.modelsFor(providerChannel().id))
        sync(account, "manual" to "latest")
        assertTrue(shared().mappings.single().mapping.catalogActive)
        assertEquals("frozen", shared().mappings.single().mapping.canonicalModelId)
        assertEquals(createdAt, modelCreatedAt("manual"))
        change(account, "manual", "auto")
        assertEquals("latest", shared().mappings.single().mapping.canonicalModelId)
    }

    @Test
    fun `unknown catalogs only retain old active and never resurrect dormant overrides`() = runBlocking {
        val known = account()
        sync(known, "m" to "family")
        change(known, "m", "manual", "frozen")
        sync(known)
        val unknown = account()
        provisioner.syncModelUnion(OAuthProvider.CHATGPT)
        assertFalse(shared().mappings.single().mapping.catalogActive)
        assertTrue(providerChannel().models.isEmpty())
        assertNull(accounts.findDetailed(unknown.id)!!.modelCatalog)
        sync(known, "new" to null)
        sync(known)
        assertEquals(listOf("new"), providerChannel().models, "unknown account retains previous active only")
        assertFalse(shared().mappings.first { it.mapping.upstreamModel == "m" }.mapping.catalogActive)
    }

    @Test
    fun `enabled mapping metadata distinguishes inherited unknown and known empty account catalogs`() = runBlocking {
        val known = account()
        provisioner.attachAccount(known.id)
        sync(known, "a" to "family-a", "b" to "family-b")
        change(known, "b", "manual", "saved-b")
        provisioner.updateSettings(known.id, models = listOf("a"))
        val unknown = account()
        provisioner.attachAccount(unknown.id)
        provisioner.updateSettings(unknown.id, models = listOf("b"))
        suspend fun enabled() = shared().mappings.filter { it.enabled }.map { it.mapping.upstreamModel }.toSet()
        assertEquals(setOf("a", "b"), enabled())
        provisioner.updateSettings(unknown.id, enabled = false)
        assertEquals(setOf("a"), enabled())
        provisioner.updateSettings(known.id, enabled = false)
        assertEquals(emptySet(), enabled())
        // 未同步且授权为空表示继承渠道目录，而不是没有授权。
        provisioner.updateSettings(unknown.id, enabled = true, models = emptyList())
        assertNull(accounts.findDetailed(unknown.id)!!.modelCatalog)
        assertEquals(setOf("a", "b"), enabled())
        // 同步到已知空目录后，即使授权为空也不能继承其他账号的目录。
        sync(unknown)
        assertEquals(emptySet(), enabled())
        provisioner.updateSettings(known.id, enabled = true)
        assertEquals(setOf("a"), enabled())
        val retained = shared().mappings.single { it.mapping.upstreamModel == "b" }
        assertFalse(retained.enabled)
        assertTrue(retained.mapping.catalogActive)
        assertEquals("saved-b", retained.mapping.canonicalModelId)
    }

    @Test
    fun `default winner uses createdAt then id and preserves null as upstream default`() = runBlocking {
        val first = account(now = 1_000)
        val second = account(now = 2_000)
        sync(first, "m" to null)
        sync(second, "m" to "later")
        accounts.updateSettings(first.id, name = "touched", now = 999_999)
        provisioner.syncModelUnion(OAuthProvider.CHATGPT)
        assertNull(shared().mappings.single().automaticCanonicalModelId)
        assertNull(shared().mappings.single().mapping.canonicalModelId)
        val a = account(now = 3_000)
        val b = account(now = 3_000)
        val winner = listOf(a, b).minBy { it.id }
        sync(a, "tie" to "a")
        sync(b, "tie" to "b")
        assertEquals(if (winner.id == a.id) "a" else "b", shared().mappings.first { it.mapping.upstreamModel == "tie" }.automaticCanonicalModelId)
    }

    @Test
    fun `null and absent automatic defaults do not advance unknown retained mapping revision`() = runBlocking {
        val known = account()
        val catalog = listOf("auto", "manual", "upstream").map { OAuthAccountModelCatalogEntry(it) }
        assertTrue(provisioner.syncModelCatalog(known.id, catalog))
        change(known, "manual", "manual", "frozen")
        change(known, "upstream", "upstream")
        val unknown = account()
        val before = shared()
        val beforeChannel = providerChannel()
        assertTrue(before.mappings.all { it.automaticCanonicalModelId == null && it.mapping.catalogActive })

        // null -> 缺失 -> null 都沿用上游；unknown 保留 active，人工覆盖也不变。
        for (nextCatalog in listOf(emptyList(), catalog)) {
            assertTrue(provisioner.syncModelCatalog(known.id, nextCatalog))
            assertEquals(nextCatalog, accounts.findDetailed(known.id)!!.modelCatalog)
            assertEquals(before, shared(), "nullable 默认键的增删不应改变共享 GET 或 revision")
            assertEquals(beforeChannel, providerChannel(), "映射 no-op 不应推进渠道更新时间")
            assertTrue(provisioner.syncModelCatalog(known.id, nextCatalog))
            assertEquals(before, shared(), "重复同步也应是 no-op")
        }
        provisioner.syncModelUnion(OAuthProvider.CHATGPT)
        assertEquals(before, shared())
        assertNull(accounts.findDetailed(unknown.id)!!.modelCatalog)
    }

    @Test
    fun `deleting null default account does not advance unknown retained mapping revision`() = runBlocking {
        val known = account()
        sync(known, "m" to null)
        val unknown = account()
        val other = ProviderChannelProvisioner(
            ChannelRepository(database.database, cipher),
            OAuthAccountRepository(database.database, OAuthProviderChannelLookup()),
        )
        val before = shared()
        val beforeChannel = providerChannel()
        val createdAt = modelCreatedAt("m")

        assertTrue(other.deleteAccount(known.id))

        val expected = before.copy(affectedAccountCount = before.affectedAccountCount - 1)
        assertEquals(expected, shared(), "账号数量变化不应把 null -> 缺失误判为模型默认变化")
        assertEquals(expected, other.modelMappings(OAuthProvider.CHATGPT))
        assertEquals(beforeChannel, providerChannel())
        assertEquals(createdAt, modelCreatedAt("m"))
        assertNull(accounts.findDetailed(known.id))
        assertNull(accounts.findDetailed(unknown.id)!!.modelCatalog)
        other.syncModelUnion(OAuthProvider.CHATGPT)
        assertEquals(expected, shared())
    }

    @Test
    fun `deleting nonnull default account advances revision for retained manual mapping`() = runBlocking {
        val known = account()
        sync(known, "m" to "family")
        change(known, "m", "manual", "frozen")
        val unknown = account()
        val before = shared()
        val beforeMappings = providerChannel().modelMappings
        assertEquals("family", before.mappings.single().automaticCanonicalModelId)

        assertTrue(provisioner.deleteAccount(known.id))

        val expected = before.copy(
            revision = before.revision + 1,
            affectedAccountCount = before.affectedAccountCount - 1,
            mappings = before.mappings.map { it.copy(automaticCanonicalModelId = null) },
        )
        assertEquals(expected, shared(), "manual bytes 不变也必须为真正的自动默认变化推进版本")
        assertEquals(beforeMappings, providerChannel().modelMappings)
        assertNull(accounts.findDetailed(unknown.id)!!.modelCatalog)
        provisioner.syncModelUnion(OAuthProvider.CHATGPT)
        assertEquals(expected, shared())
    }

    @Test
    fun `complete provider directory exceeds admin paging limits without mixing providers`() = runBlocking {
        val seed = account(now = 1_000)
        sync(seed, "seed" to null)
        suspendTransaction(database.database) {
            val row = OAuthAccounts.selectAll().where { OAuthAccounts.id eq seed.id }.single()
            (1..5_002).forEach { index ->
                OAuthAccounts.insert {
                    it[id] = UUID.randomUUID().toString()
                    it[name] = "bulk-$index"
                    it[provider] = OAuthProvider.CHATGPT.value
                    it[refreshTokenCiphertext] = row[OAuthAccounts.refreshTokenCiphertext]
                    it[refreshTokenNonce] = row[OAuthAccounts.refreshTokenNonce]
                    it[refreshTokenKeyVersion] = 1
                    it[authState] = "ACTIVE"
                    it[enabled] = false
                    it[createdAt] = 1_000L + index
                    it[authorizedAt] = 1_000L + index
                    it[updatedAt] = 0
                    it[modelCatalog] = if (index == 5_002) """[{"upstreamModel":"last","canonicalModelId":"last-family"}]""" else "[]"
                }
            }
        }
        val other = account(OAuthProvider.ANTIGRAVITY)
        provisioner.syncModelCatalog(other.id, listOf(OAuthAccountModelCatalogEntry("other-provider")))
        provisioner.syncModelUnion(OAuthProvider.CHATGPT)
        assertEquals(5_003, shared().affectedAccountCount)
        assertEquals(setOf("seed", "last"), providerChannel().models.toSet())
        assertEquals("last-family", shared().mappings.first { it.mapping.upstreamModel == "last" }.automaticCanonicalModelId)
    }

    @Test
    fun `legacy provider catalog updates and reconciliation preserve overrides and base configuration`() = runBlocking {
        val account = account()
        sync(account, "manual" to "family", "suggested" to "suggested-family", "auto" to null)
        suspendTransaction(database.database) {
            ChannelModels.update({ ChannelModels.upstreamModel eq "suggested" }) { it[mappingSource] = "models_dev" }
        }
        change(account, "manual", "manual", "frozen")
        val original = providerChannel()
        channels.update(original.id, "unchanged-custom-name", "https://local.example.invalid", authType = "google", enabled = false, priority = 37)
        val configured = providerChannel()
        val revision = configured.modelMappingRevision
        provisioner.setProviderModels(OAuthProvider.CHATGPT, listOf("manual", "fresh"))
        assertEquals("frozen", shared().mappings.first { it.mapping.upstreamModel == "manual" }.mapping.canonicalModelId)
        val suggested = shared().mappings.first { it.mapping.upstreamModel == "suggested" }
        assertFalse(suggested.mapping.catalogActive)
        assertEquals("models_dev", suggested.mapping.mappingSource)
        provisioner.setProviderModels(OAuthProvider.CHATGPT, listOf("manual", "suggested", "fresh"))
        assertFalse(shared().mappings.first { it.mapping.upstreamModel == "suggested" }.mapping.catalogActive, "legacy saves cannot resurrect dormant overrides")
        sync(account, "suggested" to "changed")
        assertEquals("suggested-family", shared().mappings.first { it.mapping.upstreamModel == "suggested" }.mapping.canonicalModelId)
        provisioner.reconcile()
        val settled = providerChannel()
        assertEquals(configured.copy(modelMappings = settled.modelMappings, updatedAt = settled.updatedAt, modelMappingRevision = settled.modelMappingRevision), settled)
        assertTrue(settled.modelMappingRevision > revision)
        val settledRevision = settled.modelMappingRevision
        provisioner.reconcile()
        assertEquals(settledRevision, providerChannel().modelMappingRevision)
    }

    @Test
    fun `provider bare replacement and update mapping bypass are rejected`() = runBlocking {
        val id = provisioner.ensure(OAuthProvider.CHATGPT)!!
        val before = providerChannel()
        assertFailsWith<IllegalArgumentException> { channels.replaceModels(id, listOf(ChannelModelRecord("unknown"))) }
        assertFailsWith<IllegalArgumentException> {
            channels.update(id, "changed", "https://invalid.example", authType = null, enabled = true, modelMappings = emptyList())
        }
        assertEquals(before, providerChannel())
    }

    @Test
    fun `two instances concurrent settings conflict rolls back the whole account and merges dirty mappings`() = runBlocking {
        val account = account()
        sync(account, "a" to null, "b" to null)
        val other = ProviderChannelProvisioner(ChannelRepository(database.database, cipher), OAuthAccountRepository(database.database, OAuthProviderChannelLookup()))
        val revision = shared().revision
        val outcomes = listOf(provisioner, other).mapIndexed { index, instance ->
            async(Dispatchers.IO) {
                runCatching {
                    instance.updateSettings(account.id, name = "winner-$index", models = listOf(if (index == 0) "a" else "b"),
                        modelMappingChanges = listOf(ProviderModelMappingChange(if (index == 0) "a" else "b", "manual", "canonical-$index")),
                        expectedModelMappingRevision = revision)
                }
            }
        }.awaitAll()
        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(1, outcomes.count { it.exceptionOrNull() is ModelMappingRevisionChangedException })
        val stored = accounts.findDetailed(account.id)!!
        val winner = stored.name.removePrefix("winner-").toInt()
        assertEquals(listOf(if (winner == 0) "a" else "b"), stored.models)
        assertEquals(revision + 1, shared().revision)
        other.updateSettings(account.id, modelMappingChanges = listOf(ProviderModelMappingChange(if (winner == 0) "b" else "a", "upstream")), expectedModelMappingRevision = shared().revision)
        assertEquals("canonical-$winner", shared().mappings.first { it.mapping.upstreamModel == if (winner == 0) "a" else "b" }.mapping.canonicalModelId)
    }

    @Test
    fun `two instances sync and settings serialize without losing manual mapping or catalog`() = runBlocking {
        val account = account()
        sync(account, "m" to "family")
        val other = ProviderChannelProvisioner(ChannelRepository(database.database, cipher), OAuthAccountRepository(database.database, OAuthProviderChannelLookup()))
        val revision = shared().revision
        val sync = async(Dispatchers.IO) { other.syncModelCatalog(account.id, listOf(OAuthAccountModelCatalogEntry("m", "latest"), OAuthAccountModelCatalogEntry("new"))) }
        val save = async(Dispatchers.IO) { runCatching { provisioner.updateSettings(account.id, modelMappingChanges = listOf(ProviderModelMappingChange("m", "manual", "frozen")), expectedModelMappingRevision = revision) } }
        assertTrue(sync.await())
        val saved = save.await()
        if (saved.isFailure) {
            assertTrue(saved.exceptionOrNull() is ModelMappingRevisionChangedException)
            change(account, "m", "manual", "frozen")
        }
        assertEquals(setOf("m", "new"), providerChannel().models.toSet())
        assertEquals("frozen", shared().mappings.first { it.mapping.upstreamModel == "m" }.mapping.canonicalModelId)
        assertEquals("latest", shared().mappings.first { it.mapping.upstreamModel == "m" }.automaticCanonicalModelId)
    }

    @Test
    fun `failed settings and sync storage roll back mappings catalog authorization and revision`() = runBlocking {
        val account = account()
        sync(account, "m" to "family")
        accounts.setModels(account.id, listOf("m"))
        val beforeAccount = accounts.findDetailed(account.id)!!
        val beforeChannel = providerChannel()
        suspendTransaction(database.database) { exec("ALTER TABLE oauth_accounts ADD CONSTRAINT test_settings_failure CHECK (name <> 'fail-settings')") }
        try {
            assertFailsWith<Exception> { provisioner.updateSettings(account.id, name = "fail-settings", models = emptyList(), modelMappingChanges = listOf(ProviderModelMappingChange("m", "manual", "frozen")), expectedModelMappingRevision = shared().revision) }
        } finally {
            suspendTransaction(database.database) { exec("ALTER TABLE oauth_accounts DROP CONSTRAINT test_settings_failure") }
        }
        assertEquals(beforeAccount, accounts.findDetailed(account.id))
        assertEquals(beforeChannel, providerChannel())
        suspendTransaction(database.database) { exec("ALTER TABLE channel_models ADD CONSTRAINT test_catalog_failure CHECK (upstream_model <> 'fail-catalog')") }
        try {
            assertFailsWith<Exception> { sync(account, "fail-catalog" to null) }
        } finally {
            suspendTransaction(database.database) { exec("ALTER TABLE channel_models DROP CONSTRAINT test_catalog_failure") }
        }
        assertEquals(beforeAccount, accounts.findDetailed(account.id))
        assertEquals(beforeChannel, providerChannel())
    }

    @Test
    fun `catalog pruning keeps explicit zero sentinel and deleting last account commits empty union`() = runBlocking {
        val account = account()
        sync(account, "m" to null)
        accounts.setModels(account.id, listOf("m"))
        change(account, "m", "manual", "frozen")
        sync(account, "replacement" to null)
        val pruned = accounts.findDetailed(account.id)!!
        assertTrue(pruned.models.isNotEmpty())
        assertFalse(pruned.supports("replacement"))
        assertFalse(pruned.supports("m"))
        assertTrue(provisioner.deleteAccount(account.id))
        assertTrue(providerChannel().models.isEmpty())
        assertFalse(shared().mappings.single().mapping.catalogActive)
        assertEquals(0, shared().affectedAccountCount)
    }

    @Test
    fun `modelsFor and saveKeys only authorize active mappings while dormant manual is retained`() = runBlocking {
        val custom = channels.create(
            name = "custom-active", baseUrl = "https://example.invalid", authType = "bearer", enabled = true,
            keys = listOf(NewChannelKey("key", cipher.encrypt("key-token"), models = listOf("active"))),
            modelMappings = listOf(ChannelModelRecord("active"), ChannelModelRecord("inactive", "frozen", "manual", catalogActive = false)),
        )
        assertEquals(listOf("active"), custom.models)
        assertEquals(setOf("active"), channels.modelsFor(custom.id))
        assertEquals(2, custom.modelMappings.size)
        assertFailsWith<IllegalArgumentException> {
            channels.update(custom.id, custom.name, custom.baseUrl, keys = listOf(dev.soven.prismllm.channel.persistence.ChannelKeyUpdate(id = custom.keys.single().id, name = "key", models = listOf("inactive"))), authType = null, enabled = true)
        }
        assertEquals(custom, channels.find(custom.id))
    }

    @Test
    fun `unknown account retains upstream auto mapping after deleting last known catalog across instances`() = runBlocking<Unit> {
        assertUnknownCatalogAutoFallback(deleteKnownAccount = true)
    }

    @Test
    fun `unknown account retains upstream auto mapping after clearing last known catalog across instances`() = runBlocking<Unit> {
        assertUnknownCatalogAutoFallback(deleteKnownAccount = false)
    }

    private suspend fun assertUnknownCatalogAutoFallback(deleteKnownAccount: Boolean) {
        val known = account()
        sync(known, "m" to "family", "manual" to "manual-default", "suggested" to "suggested-default")
        change(known, "manual", "manual", "frozen")
        suspendTransaction(database.database) {
            ChannelModels.update({ ChannelModels.upstreamModel eq "suggested" }) { it[mappingSource] = "models_dev" }
        }
        val unknown = account()
        val other = ProviderChannelProvisioner(
            ChannelRepository(database.database, cipher),
            OAuthAccountRepository(database.database, OAuthProviderChannelLookup()),
        )
        val before = shared()
        val createdAt = modelCreatedAt("m")
        assertEquals("family", before.mappings.first { it.mapping.upstreamModel == "m" }.mapping.publicModelId)
        if (deleteKnownAccount) {
            assertTrue(other.deleteAccount(known.id))
        } else {
            assertTrue(other.syncModelCatalog(known.id, emptyList()))
        }
        val after = shared()
        assertEquals(before.revision + 1, after.revision)
        assertEquals(after, other.modelMappings(OAuthProvider.CHATGPT))
        val auto = after.mappings.first { it.mapping.upstreamModel == "m" }
        assertEquals("auto", auto.mode)
        assertTrue(auto.mapping.catalogActive)
        assertNull(auto.mapping.canonicalModelId)
        assertNull(auto.automaticCanonicalModelId)
        assertNull(auto.mapping.mappingSource)
        assertEquals("m", auto.mapping.publicModelId)
        assertEquals(createdAt, modelCreatedAt("m"))
        for (model in listOf("manual", "suggested")) {
            assertEquals(
                before.mappings.first { it.mapping.upstreamModel == model }.mapping,
                after.mappings.first { it.mapping.upstreamModel == model }.mapping,
                "unknown catalog must preserve explicit mapping intent",
            )
        }
        assertNull(accounts.findDetailed(unknown.id)!!.modelCatalog)
        assertEquals(setOf("m", "manual", "suggested"), providerChannel().models.toSet())
        other.syncModelUnion(OAuthProvider.CHATGPT)
        provisioner.syncModelUnion(OAuthProvider.CHATGPT)
        if (!deleteKnownAccount) assertTrue(other.syncModelCatalog(known.id, emptyList()))
        assertEquals(after, shared(), "repeated sync must not advance the mapping revision")
    }

    @Test
    fun `provider level model mappings update and enabled toggle share channel lock`() = runBlocking<Unit> {
        val acc = account()
        sync(acc, "m" to "family-m")
        val initial = shared()
        assertTrue(providerChannel().enabled)

        val disabled = provisioner.setProviderEnabled(OAuthProvider.CHATGPT, false)
        assertNotNull(disabled)
        assertFalse(disabled.enabled)
        assertFalse(providerChannel().enabled)

        val reenabled = provisioner.setProviderEnabled(OAuthProvider.CHATGPT, true)
        assertNotNull(reenabled)
        assertTrue(reenabled.enabled)

        val updated = provisioner.updateModelMappings(
            provider = OAuthProvider.CHATGPT,
            changes = listOf(ProviderModelMappingChange("m", "manual", "custom-m")),
            expectedRevision = initial.revision,
        )
        assertEquals(initial.revision + 1, updated.revision)
        assertEquals("manual", updated.mappings.first { it.mapping.upstreamModel == "m" }.mode)
        assertEquals("custom-m", updated.mappings.first { it.mapping.upstreamModel == "m" }.mapping.publicModelId)

        assertFailsWith<ModelMappingRevisionChangedException> {
            provisioner.updateModelMappings(
                provider = OAuthProvider.CHATGPT,
                changes = listOf(ProviderModelMappingChange("m", "upstream")),
                expectedRevision = initial.revision,
            )
        }

        // 未置备的提供方只做开关时不得顺带创建渠道。
        assertNull(provisioner.setProviderEnabled(OAuthProvider.ANTIGRAVITY, false))
        assertNull(channels.findProviderChannel(OAuthProvider.ANTIGRAVITY))

        // 人工新增共享映射：先休眠保存，目录出现后由并集自动激活。
        val withNew = provisioner.updateModelMappings(
            provider = OAuthProvider.CHATGPT,
            changes = listOf(ProviderModelMappingChange("fresh", "manual", "fresh-alias")),
            expectedRevision = updated.revision,
        )
        val fresh = withNew.mappings.first { it.mapping.upstreamModel == "fresh" }
        assertEquals("manual", fresh.mode)
        assertEquals("fresh-alias", fresh.mapping.publicModelId)
        assertFalse(fresh.mapping.catalogActive)
        assertFalse("fresh" in providerChannel().models)

        assertFailsWith<IllegalArgumentException> {
            provisioner.updateModelMappings(
                provider = OAuthProvider.CHATGPT,
                changes = listOf(ProviderModelMappingChange("auto-new", "auto")),
                expectedRevision = withNew.revision,
            )
        }
        assertEquals(withNew.revision, shared().revision, "被拒绝的新增不能推进版本")

        sync(acc, "fresh" to "fresh-alias")
        val revived = shared().mappings.first { it.mapping.upstreamModel == "fresh" }
        assertTrue(revived.mapping.catalogActive)
        assertTrue("fresh" in providerChannel().models)
    }

    private suspend fun account(provider: OAuthProvider = OAuthProvider.CHATGPT, now: Long = System.currentTimeMillis()): OAuthAccountRecord = accounts.create(NewOAuthAccount(name = "account-" + UUID.randomUUID(), provider = provider, refreshToken = cipher.encrypt("refresh")), now)
    private suspend fun sync(account: OAuthAccountRecord, vararg entries: Pair<String, String?>) {
        assertTrue(provisioner.syncModelCatalog(account.id, entries.map { OAuthAccountModelCatalogEntry(it.first, it.second) }))
    }
    private suspend fun shared() = provisioner.modelMappings(OAuthProvider.CHATGPT)
    private suspend fun providerChannel() = channels.findProviderChannel(OAuthProvider.CHATGPT)!!
    private suspend fun change(account: OAuthAccountRecord, upstream: String, mode: String, canonical: String? = null) {
        assertNotNull(provisioner.updateSettings(account.id, modelMappingChanges = listOf(ProviderModelMappingChange(upstream, mode, canonical)), expectedModelMappingRevision = shared().revision))
    }
    private suspend fun modelCreatedAt(model: String): Long = suspendTransaction(database.database) {
        ChannelModels.selectAll().where { ChannelModels.upstreamModel eq model }.single()[ChannelModels.createdAt]
    }
}
