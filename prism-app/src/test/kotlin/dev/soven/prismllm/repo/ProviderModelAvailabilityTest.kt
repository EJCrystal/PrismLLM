package dev.soven.prismllm.repo

import dev.soven.prismllm.channel.domain.ChannelKind
import dev.soven.prismllm.channel.domain.ChannelModelRecord
import dev.soven.prismllm.channel.persistence.ChannelRepository
import dev.soven.prismllm.channel.persistence.ChannelModels
import dev.soven.prismllm.oauth.domain.OAuthAccountModelCatalogEntry
import dev.soven.prismllm.oauth.persistence.NewOAuthAccount
import dev.soven.prismllm.oauth.persistence.OAuthAccountRepository
import dev.soven.prismllm.oauth.persistence.OAuthAccounts
import dev.soven.prismllm.channel.persistence.Channels
import dev.soven.prismllm.routing.domain.candidate.RoutingAccessScope
import dev.soven.prismllm.routing.readmodel.OAuthAccountDirectory
import dev.soven.prismllm.routing.readmodel.UpstreamModelCatalog
import dev.soven.prismllm.shared.crypto.CredentialCipher
import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.shared.domain.OAuthProvider
import dev.soven.prismllm.testsupport.db.SharedPostgres
import dev.soven.prismllm.testsupport.db.TestDatabase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
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
    private lateinit var catalog: UpstreamModelCatalog
    private val cipher = CredentialCipher(ByteArray(32) { 13 })

    @BeforeAll
    fun start() {
        val postgres = SharedPostgres.container
        factory = TestDatabase(SharedPostgres.newDatabase(), postgres.username, postgres.password)
        channels = ChannelRepository(factory.database, cipher)
        accounts = OAuthAccountRepository(factory.database, OAuthProviderChannelLookup())
        catalog = UpstreamModelCatalog(channels, OAuthAccountDirectory { accounts.listByChannel() })
    }

    @AfterAll
    fun stop() {
        if (::factory.isInitialized) factory.close()
    }

    @BeforeEach
    fun clear() = runBlocking<Unit> {
        suspendTransaction(factory.database) {
            OAuthAccounts.deleteAll()
            Channels.deleteAll()
        }
    }

    private suspend fun channel(mappings: List<ChannelModelRecord>) = channels.create(
        name = "provider-models",
        baseUrl = "https://example.invalid/v1",
        keys = emptyList(),
        authType = "bearer",
        protocols = listOf(GatewayProtocol.RESPONSES),
        enabled = true,
        modelMappings = mappings,
        kind = ChannelKind.PROVIDER,
        provider = OAuthProvider.CHATGPT,
    )

    private suspend fun account(channelId: String, models: List<String> = emptyList()) = accounts.create(
        NewOAuthAccount(
            name = "unsynchronized",
            provider = OAuthProvider.CHATGPT,
            refreshToken = cipher.encrypt("test-refresh-token"),
            channelIds = listOf(channelId),
            models = models,
        ),
    )

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
}
