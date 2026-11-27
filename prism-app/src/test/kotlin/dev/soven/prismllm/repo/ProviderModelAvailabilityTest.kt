package dev.soven.prismllm.repo

import dev.soven.prismllm.channel.domain.ChannelKind
import dev.soven.prismllm.channel.domain.ChannelModelRecord
import dev.soven.prismllm.channel.persistence.ChannelKeyModels
import dev.soven.prismllm.channel.persistence.ChannelRepository
import dev.soven.prismllm.channel.persistence.ChannelModels
import dev.soven.prismllm.channel.persistence.Channels
import dev.soven.prismllm.channel.persistence.NewChannelKey
import dev.soven.prismllm.oauth.domain.OAuthAccountModelCatalogEntry
import dev.soven.prismllm.oauth.persistence.AgyProfileRegistration
import dev.soven.prismllm.oauth.persistence.NewOAuthAccount
import dev.soven.prismllm.oauth.persistence.OAuthAccountRepository
import dev.soven.prismllm.oauth.persistence.OAuthAccounts
import dev.soven.prismllm.protocol.ProtocolConversion
import dev.soven.prismllm.routing.domain.candidate.RoutingAccessScope
import dev.soven.prismllm.routing.readmodel.CredentialRouteIdentity
import dev.soven.prismllm.routing.readmodel.CredentialRouteType
import dev.soven.prismllm.routing.readmodel.UpstreamModelCatalog
import dev.soven.prismllm.shared.crypto.CredentialCipher
import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.shared.domain.OAuthProvider
import dev.soven.prismllm.testsupport.db.SharedPostgres
import dev.soven.prismllm.testsupport.db.TestDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.GlobalStatementInterceptor
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("component")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProviderModelAvailabilityTest {
    private lateinit var factory: TestDatabase
    private lateinit var channels: ChannelRepository
    private lateinit var accounts: OAuthAccountRepository
    private lateinit var routes: ModelCredentialRouteRepository
    private lateinit var catalog: UpstreamModelCatalog
    private val cipher = CredentialCipher(ByteArray(32) { 13 })

    @BeforeAll
    fun start() {
        val postgres = SharedPostgres.container
        factory = TestDatabase(SharedPostgres.newDatabase(), postgres.username, postgres.password)
        channels = ChannelRepository(factory.database, cipher)
        accounts = OAuthAccountRepository(factory.database, OAuthProviderChannelLookup())
        routes = ModelCredentialRouteRepository(factory.database)
        catalog = UpstreamModelCatalog(RoutingConfigurationSnapshotRepository(factory.database, channels, accounts, routes))
    }

    @AfterAll
    fun stop() {
        if (::factory.isInitialized) factory.close()
    }

    @BeforeEach
    fun clear() = runBlocking<Unit> {
        suspendTransaction(factory.database) {
            ModelCredentialRouteEntries.deleteAll()
            ModelCredentialRoutes.deleteAll()
            OAuthAccounts.deleteAll()
            Channels.deleteAll()
        }
    }

    private suspend fun channel(
        mappings: List<ChannelModelRecord>,
        name: String = "provider-models",
        keys: List<NewChannelKey> = emptyList(),
        protocols: List<GatewayProtocol> = listOf(GatewayProtocol.RESPONSES),
        kind: ChannelKind = ChannelKind.PROVIDER,
        provider: OAuthProvider = OAuthProvider.CHATGPT,
    ) = channels.create(
        name = name,
        baseUrl = "https://example.invalid/v1",
        keys = keys,
        authType = "bearer",
        protocols = protocols,
        enabled = true,
        modelMappings = mappings,
        kind = kind,
        provider = provider.takeIf { kind == ChannelKind.PROVIDER },
    )

    private suspend fun account(
        channelId: String,
        models: List<String> = emptyList(),
        provider: OAuthProvider = OAuthProvider.CHATGPT,
    ) = accounts.create(
        NewOAuthAccount(
            name = "unsynchronized",
            provider = provider,
            refreshToken = cipher.encrypt("test-refresh-token"),
            channelIds = listOf(channelId),
            models = models,
        ),
    ).also { account ->
        val channel = channels.find(channelId)!!
        channel.modelMappings.map { it.publicModelId }.distinct().forEach { modelId ->
            val current = routes.find(modelId)
            val identity = CredentialRouteIdentity(channelId, CredentialRouteType.OAUTH, account.id)
            routes.save(modelId, current?.revision ?: 0, (current?.entries.orEmpty() + identity).distinct())
        }
    }

    @Test
    fun `agy profile is routable through ordinary protocols converted to chat and keeps its route identity`() = runBlocking {
        val channel = channel(
            mappings = listOf(ChannelModelRecord("agy-upstream", "agy-public", "manual")),
            name = "antigravity-provider",
            protocols = listOf(GatewayProtocol.GEMINI),
            provider = OAuthProvider.ANTIGRAVITY,
        )
        val legacy = account(channel.id, models = listOf("agy-upstream"), provider = OAuthProvider.ANTIGRAVITY)
        val savedRoute = routes.find("agy-public")!!
        val migrated = accounts.registerAgyProfile(
            AgyProfileRegistration(
                profileId = UUID.randomUUID().toString(),
                sourceAccountId = legacy.id,
                name = "migrated-agy",
                subscriptionTier = "PRO",
                currentModelId = "agy-upstream",
            ),
        )

        assertTrue(migrated.isAgy)
        assertNull(migrated.refreshTokenCiphertext)
        assertEquals(savedRoute, routes.find("agy-public"))
        val chatCompatible = GatewayProtocol.entries.filter {
            ProtocolConversion.supports(it, GatewayProtocol.CHAT_COMPLETIONS)
        }
        assertTrue(chatCompatible.isNotEmpty())
        chatCompatible.forEach { protocol ->
            assertEquals(
                listOf(migrated.id),
                catalog.resolve("agy-public", protocol).candidates.map { it.credential.id },
                "agy ACP should serve $protocol through the ordinary routed model",
            )
        }
        GatewayProtocol.entries.filterNot { it in chatCompatible }.forEach { protocol ->
            assertTrue(catalog.resolve("agy-public", protocol).candidates.isEmpty(), "$protocol is not convertible to ACP chat")
        }
    }

    @Test
    fun `inactive mappings stay not configured in every scope even when an unsynchronized account supports them`() = runBlocking {
        val channel = channel(listOf(
            ChannelModelRecord("active-upstream", "active-public", "manual"),
            ChannelModelRecord("missing-upstream", "missing-public", "manual", catalogActive = false),
        ))
        val account = account(channel.id)
        assertNull(account.modelCatalog)
        assertTrue(account.supports("missing-upstream"))
        assertEquals(2, channels.find(channel.id)!!.modelMappings.size)
        assertEquals(listOf("active-upstream"), channels.find(channel.id)!!.models)
        assertEquals(setOf("active-upstream"), channels.modelsFor(channel.id))
        assertEquals(listOf("active-public"), catalog.listEnabledModels())
        assertTrue(catalog.findCandidates("missing-public", GatewayProtocol.RESPONSES).isEmpty())
        val scopes = listOf(
            RoutingAccessScope(),
            RoutingAccessScope(allowedChannelIds = setOf(channel.id), allowedModelIds = setOf("missing-public")),
            RoutingAccessScope(allowedModelIds = emptySet()),
            RoutingAccessScope(allowedChannelIds = emptySet(), allowedModelIds = setOf("missing-public")),
        )
        scopes.forEach { scope ->
            assertTrue(catalog.listDirectModels(scope).none { it.publicId == "missing-public" })
            val resolved = catalog.resolve("missing-public", GatewayProtocol.RESPONSES, scope = scope)
            assertFalse(resolved.configured, scope.toString())
            assertFalse(resolved.protocolReachable)
            assertFalse(resolved.accessDenied)
            assertFalse(resolved.modelAccessDenied)
            assertTrue(resolved.candidates.isEmpty())
            assertNull(resolved.retryAt)
        }
        assertTrue(catalog.findCandidates("missing-upstream", null).isEmpty())
    }

    @Test
    fun `reactivating a saved mapping restores canonical candidates only within caller scope`() = runBlocking {
        val mapping = ChannelModelRecord("upstream-variant", "canonical-family", "manual", catalogActive = false)
        val channel = channel(listOf(mapping))
        account(channel.id)
        assertTrue(catalog.listEnabledModels().isEmpty())
        suspendTransaction(factory.database) {
            assertEquals(1, ChannelModels.update({
                (ChannelModels.channelId eq channel.id) and (ChannelModels.upstreamModel eq mapping.upstreamModel)
            }) { it[catalogActive] = true })
        }
        assertEquals(listOf("canonical-family"), catalog.listEnabledModels())
        val allowed = RoutingAccessScope(allowedChannelIds = setOf(channel.id), allowedModelIds = setOf("canonical-family"))
        val resolved = catalog.resolve("canonical-family", GatewayProtocol.RESPONSES, scope = allowed)
        assertTrue(resolved.configured)
        assertTrue(resolved.protocolReachable)
        assertFalse(resolved.accessDenied)
        assertEquals(listOf("upstream-variant"), resolved.candidates.map { it.upstreamModel })
        assertEquals(listOf("canonical-family"), resolved.candidates.map { it.pricingModelId })
        val deniedScopes = listOf(
            RoutingAccessScope(allowedChannelIds = setOf(channel.id), allowedModelIds = emptySet()) to true,
            RoutingAccessScope(allowedChannelIds = emptySet(), allowedModelIds = setOf("canonical-family")) to false,
        )
        deniedScopes.forEach { (scope, modelDenied) ->
            val denied = catalog.resolve("canonical-family", GatewayProtocol.RESPONSES, scope = scope)
            assertFalse(denied.configured)
            assertTrue(denied.accessDenied)
            assertEquals(modelDenied, denied.modelAccessDenied)
            assertTrue(denied.candidates.isEmpty())
            assertTrue(catalog.listDirectModels(scope).isEmpty())
        }
        assertFalse(catalog.resolve("upstream-variant", GatewayProtocol.RESPONSES, scope = allowed).configured)
    }

    @Test
    fun `selected credential aliases stay adjacent in saved identity order and ignore legacy priorities`() = runBlocking {
        val channel = channel(listOf(
            ChannelModelRecord("upstream-z", "shared/public", "manual"),
            ChannelModelRecord("upstream-a", "shared/public", "manual"),
        ))
        val first = account(channel.id)
        val second = account(channel.id)
        val entries = listOf(second, first).map { CredentialRouteIdentity(channel.id, CredentialRouteType.OAUTH, it.id) }
        routes.save("shared/public", routes.find("shared/public")!!.revision, entries)
        val candidates = catalog.resolve("shared/public", GatewayProtocol.CHAT_COMPLETIONS).candidates
        assertEquals(
            listOf(second.id to "upstream-a", second.id to "upstream-z", first.id to "upstream-a", first.id to "upstream-z"),
            candidates.map { it.credential.id to it.upstreamModel },
        )
        assertEquals(listOf(0, 0, 1, 1), candidates.map { it.position })
        assertEquals(listOf("shared/public"), catalog.listEnabledModels())
    }

    @Test
    fun `unselected account cannot restore an empty route or bypass a selected identity scope`() = runBlocking {
        val channel = channel(listOf(ChannelModelRecord("upstream", "canonical", "manual")))
        val selected = account(channel.id)
        val omitted = account(channel.id)
        routes.save("canonical", routes.find("canonical")!!.revision,
            listOf(CredentialRouteIdentity(channel.id, CredentialRouteType.OAUTH, selected.id)))
        assertEquals(listOf(selected.id), catalog.resolve("canonical", GatewayProtocol.RESPONSES).candidates.map { it.credential.id })
        routes.save("canonical", routes.find("canonical")!!.revision, emptyList())
        assertTrue(catalog.resolve("canonical", GatewayProtocol.RESPONSES).candidates.isEmpty())
        assertTrue(catalog.listEnabledModels().isEmpty())
        assertTrue(accounts.findDetailed(omitted.id)!!.enabled)
    }

    @Test
    fun `selected unavailable credential preserves position when reenabled`() = runBlocking {
        val channel = channel(listOf(ChannelModelRecord("upstream", "canonical", "manual")))
        val first = account(channel.id)
        val second = account(channel.id)
        val route = routes.find("canonical")!!
        routes.save("canonical", route.revision,
            listOf(CredentialRouteIdentity(channel.id, CredentialRouteType.OAUTH, second.id),
                CredentialRouteIdentity(channel.id, CredentialRouteType.OAUTH, first.id)))
        suspendTransaction(factory.database) {
            OAuthAccounts.update({ OAuthAccounts.id eq second.id }) { it[enabled] = false }
        }
        assertEquals(listOf(first.id), catalog.resolve("canonical", GatewayProtocol.RESPONSES).candidates.map { it.credential.id })
        suspendTransaction(factory.database) {
            OAuthAccounts.update({ OAuthAccounts.id eq second.id }) { it[enabled] = true }
        }
        assertEquals(listOf(second.id, first.id), catalog.resolve("canonical", GatewayProtocol.RESPONSES).candidates.map { it.credential.id })
    }

    @Test
    fun `shared public id intersects caller scope with each account upstream authorization and catalog`() = runBlocking {
        val channel = channel(listOf(
            ChannelModelRecord("upstream-a", "shared-public", "manual"),
            ChannelModelRecord("upstream-b", "shared-public", "manual"),
            ChannelModelRecord("upstream-c", "other-public", "manual"),
        ))
        val first = account(channel.id, listOf("upstream-a", "upstream-c"))
        val second = account(channel.id, listOf("upstream-b"))
        assertTrue(accounts.saveModelCatalog(first.id, listOf(
            OAuthAccountModelCatalogEntry("upstream-a"),
            OAuthAccountModelCatalogEntry("upstream-b"),
        )))
        assertTrue(accounts.saveModelCatalog(second.id, listOf(OAuthAccountModelCatalogEntry("upstream-b"))))
        val scope = RoutingAccessScope(allowedChannelIds = setOf(channel.id), allowedModelIds = setOf("shared-public"))
        assertEquals(listOf("shared-public"), catalog.listEnabledModels())
        assertEquals(listOf("shared-public"), catalog.listDirectModels(scope).map { it.publicId }.distinct())
        val candidates = catalog.resolve("shared-public", GatewayProtocol.RESPONSES, scope = scope).candidates
        assertEquals(setOf(first.id to "upstream-a", second.id to "upstream-b"),
            candidates.map { it.credential.id to it.upstreamModel }.toSet())
        assertEquals(setOf("shared-public"), candidates.map { it.pricingModelId }.toSet())
        val savedFirst = accounts.findDetailed(first.id)!!
        assertEquals(listOf("upstream-a", "upstream-c"), savedFirst.models)
        assertFalse(savedFirst.supports("upstream-b"), "catalog membership must not expand explicit upstream authorization")
        assertFalse(savedFirst.supports("upstream-c"), "explicit authorization must not bypass the account catalog")
        assertEquals(listOf("upstream-b"), accounts.findDetailed(second.id)!!.models)
        assertTrue(catalog.resolve("other-public", GatewayProtocol.RESPONSES, scope = scope).modelAccessDenied)
        assertTrue(catalog.resolve("shared-public", GatewayProtocol.RESPONSES,
            scope = scope.copy(allowedChannelIds = emptySet())).accessDenied)
        assertFalse(catalog.resolve("upstream-a", GatewayProtocol.RESPONSES, scope = scope).configured)
    }

    @Test
    fun `one routed snapshot never mixes credential authorization with a later saved route`() = runBlocking {
        val model = "snapshot/static"
        val readers = listOf<suspend () -> List<String>>(
            { catalog.resolve(model, GatewayProtocol.CHAT_COMPLETIONS).candidates.map { it.upstreamModel } },
            { catalog.findCandidates(model, GatewayProtocol.CHAT_COMPLETIONS).map { it.upstreamModel } },
            { catalog.listDirectModels().filter { it.publicId == model }.map { it.upstreamModel } },
        )
        for (read in readers) {
            resetStorage()
            val channel = channel(
                mappings = listOf(
                    ChannelModelRecord("u1", model, "manual"),
                    ChannelModelRecord("u2", model, "manual"),
                ),
                name = "snapshot-static",
                keys = listOf(NewChannelKey("static", cipher.encrypt("snapshot-static-secret"), models = listOf("u1"))),
                protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS),
                kind = ChannelKind.CUSTOM,
            )
            val key = channel.keys.single()
            routes.save(model, 0, emptyList())

            val old = pausedRead(
                writer = {
                    suspendTransaction(factory.database) {
                        ChannelKeyModels.deleteWhere { ChannelKeyModels.keyId eq key.id }
                        ChannelKeyModels.insert {
                            it[ChannelKeyModels.keyId] = key.id
                            it[ChannelKeyModels.upstreamModel] = "u2"
                            it[ChannelKeyModels.createdAt] = System.currentTimeMillis()
                        }
                        routes.save(
                            model,
                            routes.findInTransaction(model)!!.revision,
                            listOf(CredentialRouteIdentity(channel.id, CredentialRouteType.STATIC, key.id)),
                        )
                    }
                },
                read = read,
            )

            assertEquals(emptyList(), old, "a paused read must not combine old u1 authorization with the new route")
            assertEquals(listOf("u2"), read(), "the next read must observe the committed u2 authorization and route")
        }
    }

    @Test
    fun `oauth links and model authorization share the same routed snapshot`() = runBlocking {
        resetStorage()
        val model = "snapshot/oauth"
        val primary = channel(
            mappings = listOf(
                ChannelModelRecord("u1", model, "manual"),
                ChannelModelRecord("u2", model, "manual"),
            ),
            name = "snapshot-oauth-primary",
            protocols = listOf(GatewayProtocol.RESPONSES),
            kind = ChannelKind.CUSTOM,
        )
        val secondary = channel(
            mappings = listOf(ChannelModelRecord("u2", model, "manual")),
            name = "snapshot-oauth-secondary",
            protocols = listOf(GatewayProtocol.RESPONSES),
            kind = ChannelKind.CUSTOM,
        )
        val account = accounts.create(
            NewOAuthAccount(
                name = "snapshot-account",
                provider = OAuthProvider.CHATGPT,
                refreshToken = cipher.encrypt("snapshot-oauth-refresh"),
                channelIds = listOf(primary.id),
                models = listOf("u1"),
            ),
        )
        routes.save(model, 0, emptyList())

        val old = pausedRead(
            writer = {
                suspendTransaction(factory.database) {
                    accounts.setModelsInTransaction(account.id, listOf("u2"))
                    accounts.setChannelsInTransaction(account.id, listOf(primary.id, secondary.id))
                    routes.save(
                        model,
                        routes.findInTransaction(model)!!.revision,
                        listOf(
                            CredentialRouteIdentity(primary.id, CredentialRouteType.OAUTH, account.id),
                            CredentialRouteIdentity(secondary.id, CredentialRouteType.OAUTH, account.id),
                        ),
                    )
                }
            },
            read = {
                catalog.resolve(model, GatewayProtocol.RESPONSES).candidates
                    .map { it.channel.id to it.upstreamModel }
            },
        )

        assertEquals(emptyList(), old, "a paused read must not combine old u1 authorization with the new oauth links and route")
        assertEquals(
            listOf(primary.id to "u2", secondary.id to "u2"),
            catalog.resolve(model, GatewayProtocol.RESPONSES).candidates.map { it.channel.id to it.upstreamModel },
        )
        assertEquals(
            setOf(primary.id, secondary.id),
            catalog.listDirectModels().filter { it.publicId == model }.map { it.channel.id }.toSet(),
        )
    }

    @Test
    fun `oauth routes expose provider protocols while static keys keep the channel protocols`() = runBlocking {
        resetStorage()
        val model = "snapshot/protocol-oauth"
        val oauthChannel = channel(
            mappings = listOf(ChannelModelRecord("u1", model, "manual")),
            name = "snapshot-protocol-oauth",
            protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES),
            kind = ChannelKind.CUSTOM,
        )
        val account = accounts.create(
            NewOAuthAccount(
                name = "snapshot-protocol-account",
                provider = OAuthProvider.CHATGPT,
                refreshToken = cipher.encrypt("snapshot-protocol-refresh"),
                channelIds = listOf(oauthChannel.id),
            ),
        )
        routes.save(
            model,
            0,
            listOf(CredentialRouteIdentity(oauthChannel.id, CredentialRouteType.OAUTH, account.id)),
        )

        val oauthCandidate = catalog.resolve(model, GatewayProtocol.CHAT_COMPLETIONS).candidates.single()
        assertEquals(listOf(GatewayProtocol.RESPONSES), oauthCandidate.channel.protocols)
        assertEquals(
            listOf(GatewayProtocol.RESPONSES),
            catalog.listDirectModels().single { it.publicId == model }.channel.protocols,
        )
        val revision = routes.find(model)!!.revision

        suspendTransaction(factory.database) {
            Channels.update({ Channels.id eq oauthChannel.id }) { it[Channels.protocols] = GatewayProtocol.GEMINI.value }
        }
        assertTrue(catalog.resolve(model, GatewayProtocol.RESPONSES).candidates.isEmpty())
        assertTrue(catalog.listDirectModels().none { it.publicId == model })
        assertEquals(revision, routes.find(model)!!.revision)

        suspendTransaction(factory.database) {
            Channels.update({ Channels.id eq oauthChannel.id }) {
                it[Channels.protocols] = listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES).joinToString(",") { protocol -> protocol.value }
            }
        }
        val restored = catalog.resolve(model, GatewayProtocol.CHAT_COMPLETIONS).candidates.single()
        assertEquals(0, restored.position)
        assertEquals(listOf(GatewayProtocol.RESPONSES), restored.channel.protocols)

        val staticModel = "snapshot/protocol-static"
        val staticChannel = channel(
            mappings = listOf(ChannelModelRecord("u2", staticModel, "manual")),
            name = "snapshot-protocol-static",
            keys = listOf(NewChannelKey("static", cipher.encrypt("snapshot-protocol-static-secret"), models = listOf("u2"))),
            protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES),
            kind = ChannelKind.CUSTOM,
        )
        routes.save(
            staticModel,
            0,
            listOf(CredentialRouteIdentity(staticChannel.id, CredentialRouteType.STATIC, staticChannel.keys.single().id)),
        )
        assertEquals(
            listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES),
            catalog.resolve(staticModel, GatewayProtocol.CHAT_COMPLETIONS).candidates.single().channel.protocols,
        )
    }

    @Test
    fun `many credentials on one channel resolve in saved order`() = runBlocking {
        resetStorage()
        val model = "scale/static"
        val keys = (0 until 300).map { index ->
            NewChannelKey("key-$index", cipher.encrypt("scale-static-secret"), models = listOf("u1"))
        }
        val channel = channel(
            mappings = listOf(ChannelModelRecord("u1", model, "manual")),
            name = "scale-static",
            keys = keys,
            protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS),
            kind = ChannelKind.CUSTOM,
        )
        val entries = channel.keys.map { CredentialRouteIdentity(channel.id, CredentialRouteType.STATIC, it.id) }
        routes.save(model, 0, entries)

        assertEquals(entries.map { it.credentialId }, catalog.resolve(model, GatewayProtocol.CHAT_COMPLETIONS).candidates.map { it.credential.id })
        assertEquals(entries.map { it.credentialId }, catalog.findCandidates(model, GatewayProtocol.CHAT_COMPLETIONS).map { it.credential.id })
    }

    private suspend fun resetStorage() = suspendTransaction(factory.database) {
        ModelCredentialRouteEntries.deleteAll()
        ModelCredentialRoutes.deleteAll()
        OAuthAccounts.deleteAll()
        Channels.deleteAll()
    }

    /**
     * Pause the first routed SELECT after channel/OAuth reads, let a separate writer commit, then
     * release the reader. The read must use one PostgreSQL snapshot, not rows assembled per query.
     */
    private suspend fun <T> pausedRead(writer: suspend () -> Unit, read: suspend () -> T): T {
        val paused = CountDownLatch(1)
        val release = CountDownLatch(1)
        val armed = AtomicBoolean(true)
        val interceptor = object : GlobalStatementInterceptor {
            override fun beforeExecution(transaction: Transaction, context: StatementContext) {
                if (!armed.get()) return
                val sql = context.sql(transaction).trimStart()
                if (sql.startsWith("SELECT", ignoreCase = true) &&
                    sql.contains("model_credential_routes", ignoreCase = true) &&
                    armed.compareAndSet(true, false)
                ) {
                    paused.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "paused routing reader was not released" }
                }
            }
        }
        JdbcTransaction.globalInterceptors.add(interceptor)
        try {
            return coroutineScope {
                val reader = async(Dispatchers.IO) { read() }
                assertTrue(paused.await(10, TimeUnit.SECONDS), "routing reader did not reach the saved-route query")
                writer()
                release.countDown()
                reader.await()
            }
        } finally {
            release.countDown()
            JdbcTransaction.globalInterceptors.remove(interceptor)
        }
    }
}
