package dev.soven.prismllm.repo

import dev.soven.prismllm.channel.persistence.ChannelRepository
import dev.soven.prismllm.config.GatewayConfig
import dev.soven.prismllm.db.DatabaseFactory
import dev.soven.prismllm.oauth.ProviderChannelProvisioner
import dev.soven.prismllm.oauth.persistence.NewOAuthAccount
import dev.soven.prismllm.oauth.persistence.OAuthAccountCredentialUpdate
import dev.soven.prismllm.oauth.persistence.OAuthAccountRepository
import dev.soven.prismllm.oauth.persistence.OAuthAccounts
import dev.soven.prismllm.oauth.domain.OAuthAccountModelCatalogEntry
import dev.soven.prismllm.oauth.domain.OAuthAccountRecord
import dev.soven.prismllm.oauth.domain.OAuthAuthState
import dev.soven.prismllm.oauth.domain.OAuthQuotaGroup
import dev.soven.prismllm.oauth.domain.OAuthQuotaSnapshot
import dev.soven.prismllm.oauth.domain.OAuthQuotaWindow
import dev.soven.prismllm.shared.domain.OAuthProvider
import dev.soven.prismllm.shared.crypto.CredentialCipher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 管理面读取模型目录时只挑可用的账号：状态条件必须在数据库里生效，
 * 否则前面的不可用账号会把后面的正常账号挤出 limit。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("component")
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

    @BeforeEach
    fun resetAccounts() = runBlocking {
        suspendTransaction(factory.database) { OAuthAccounts.deleteAll() }
        Unit
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

    @Test
    fun `upstream context snapshot CAS rejects a replaced authorization`() = runBlocking {
        val original = create("context-cas", enabled = true)
        val replacement = cipher.encrypt("replacement-refresh")
        repository.updateCredentials(
            original.id,
            OAuthAccountCredentialUpdate(provider = original.provider, refreshToken = replacement),
            now = original.createdAt + 1_000,
        )

        assertFalse(
            repository.saveUpstreamContextIfSnapshotMatches(
                expectedAccount = original,
                project = "stale-project",
                tier = "stale-tier",
                checkedAt = original.createdAt + 2_000,
            ),
        )
        assertEquals(OAuthAccountRepository.UpstreamContext(null, null, null), repository.upstreamContext(original.id))

        val current = repository.find(original.id)!!
        assertTrue(
            repository.saveUpstreamContextIfSnapshotMatches(
                expectedAccount = current,
                project = "current-project",
                tier = "current-tier",
                checkedAt = original.createdAt + 3_000,
            ),
        )
        assertEquals(
            OAuthAccountRepository.UpstreamContext("current-project", "current-tier", original.createdAt + 3_000),
            repository.upstreamContext(original.id),
        )
    }

    @Test
    fun `quota groups survive persistence for grouped Antigravity limits`() = runBlocking {
        val account = create("groups", enabled = true)
        val snapshot = OAuthQuotaSnapshot(
            primary = OAuthQuotaWindow(1.5, 18_000, 10_000),
            secondary = OAuthQuotaWindow(2.5, 604_800, 20_000),
            groups = listOf(
                OAuthQuotaGroup(
                    "Gemini",
                    listOf(OAuthQuotaWindow(1.5, 18_000, 10_000), OAuthQuotaWindow(2.5, 604_800, 20_000)),
                ),
                OAuthQuotaGroup("Claude & GPT", listOf(OAuthQuotaWindow(0.0, 18_000, null))),
            ),
            checkedAt = 5_000,
        )

        repository.saveQuota(account.id, snapshot)

        assertEquals(snapshot.groups, repository.findDetailed(account.id)!!.quota!!.groups)
    }

    @Test
    fun `oversized quota groups are trimmed before encoding`() = runBlocking {
        val account = create("oversized", enabled = true)
        val groups = (1..20).map { index ->
            OAuthQuotaGroup(
                name = "G".repeat(200) + index,
                windows = (1..10).map { OAuthQuotaWindow(1.0, 18_000, null) },
            )
        }

        repository.saveQuota(account.id, OAuthQuotaSnapshot(groups = groups, checkedAt = 5_000))

        val stored = repository.findDetailed(account.id)!!.quota!!.groups
        assertEquals(16, stored.size)
        assertEquals(100, stored.first().name.length)
        assertEquals(8, stored.first().windows.size)
    }

    @Test
    fun `account model catalog is stored per account and drives routing support`() = runBlocking {
        val pixel = create("pixel", enabled = true)
        val full = create("full", enabled = true)

        repository.saveModelCatalog(
            pixel.id,
            listOf(OAuthAccountModelCatalogEntry("gemini-3.8-flash-high", "gemini-3.8-flash")),
            now = 9_000,
        )
        repository.saveModelCatalog(
            full.id,
            listOf(
                OAuthAccountModelCatalogEntry("gemini-3.8-flash-high", "gemini-3.8-flash"),
                OAuthAccountModelCatalogEntry("claude-opus-5-5-high", "claude-opus-5-5"),
            ),
            now = 10_000,
        )

        val storedPixel = repository.findDetailed(pixel.id)!!
        val storedFull = repository.findDetailed(full.id)!!
        assertEquals(listOf("gemini-3.8-flash-high"), storedPixel.modelCatalog?.map { it.upstreamModel })
        assertEquals(9_000, storedPixel.modelCatalogSyncedAt)
        assertEquals(2, storedFull.modelCatalog?.size)
        // 目录按账号生效：正价账号能调的模型不会因为 pixel 账号没同步而被放行。
        assertTrue(storedPixel.supports("gemini-3.8-flash-high"))
        assertFalse(storedPixel.supports("claude-opus-5-5-high"))
        assertTrue(storedFull.supports("claude-opus-5-5-high"))
        // 未同步目录的账号沿用渠道模型。
        val untouched = create("untouched", enabled = true)
        assertNull(repository.findDetailed(untouched.id)!!.modelCatalog)
        assertTrue(repository.findDetailed(untouched.id)!!.supports("claude-opus-5-5-high"))
    }

    @Test
    fun `channel union keeps models from every account under concurrent sync`() = runBlocking {
        val channels = ChannelRepository(factory.database, cipher)
        val provisioner = ProviderChannelProvisioner(channels, repository)
        val channelId = provisioner.ensure(OAuthProvider.ANTIGRAVITY)!!
        val pixel = create("union-pixel", enabled = true)
        val full = create("union-full", enabled = true)
        repository.saveModelCatalog(
            pixel.id,
            listOf(OAuthAccountModelCatalogEntry("gemini-3-flash-union")),
            now = 1_000,
        )
        repository.saveModelCatalog(
            full.id,
            listOf(
                OAuthAccountModelCatalogEntry("claude-opus-5-5-union", "claude-opus-5-5"),
                OAuthAccountModelCatalogEntry("gemini-3-flash-union"),
            ),
            now = 2_000,
        )

        // 并发重算被串行化：不能出现一个旧快照把另一个账号刚写入的模型覆盖掉。
        coroutineScope {
            launch { provisioner.syncModelUnion(OAuthProvider.ANTIGRAVITY) }
            launch { provisioner.syncModelUnion(OAuthProvider.ANTIGRAVITY) }
        }
        val union = channels.find(channelId)!!.modelMappings.map { it.upstreamModel }.toSet()
        assertTrue(union.containsAll(setOf("gemini-3-flash-union", "claude-opus-5-5-union")))

        repository.saveModelCatalog(
            full.id,
            listOf(OAuthAccountModelCatalogEntry("gemini-4-argon-union", "gemini-4-argon")),
            now = 3_000,
        )
        provisioner.syncModelUnion(OAuthProvider.ANTIGRAVITY)
        val updated = channels.find(channelId)!!.modelMappings.map { it.upstreamModel }.toSet()
        assertTrue(updated.contains("gemini-4-argon-union"))
        assertTrue(updated.contains("gemini-3-flash-union"))
    }

    @Test
    fun `authorized time changes only when the refresh token is replaced`() = runBlocking {
        val account = create("authorized", enabled = true)
        assertEquals(account.createdAt, account.authorizedAt)

        // 日常刷新只换 access token，即使轮换了 refresh token 也仍是同一次授权。
        val refreshed = repository.saveRefreshed(
            accountId = account.id,
            accessToken = cipher.encrypt("new-access"),
            expiresAt = account.createdAt + 3_600_000,
            tokenType = "Bearer",
            scope = null,
            rotatedRefreshToken = cipher.encrypt("rotated-refresh"),
            now = account.createdAt + 1_000,
        )!!
        assertEquals(account.authorizedAt, refreshed.authorizedAt)

        // 只补 access token、不换 refresh token 同样不算重新授权。
        val accessOnly = repository.updateCredentials(
            accountId = account.id,
            input = OAuthAccountCredentialUpdate(
                provider = account.provider,
                accessToken = cipher.encrypt("imported-access"),
                accessTokenExpiresAt = account.createdAt + 7_200_000,
            ),
            now = account.createdAt + 2_000,
        )!!
        assertEquals(account.authorizedAt, accessOnly.authorizedAt)

        // 导入新的 refresh token 才是新的授权，授权时间随本次操作更新。
        val reauthorized = repository.updateCredentials(
            accountId = account.id,
            input = OAuthAccountCredentialUpdate(
                provider = account.provider,
                refreshToken = cipher.encrypt("new-refresh"),
            ),
            now = account.createdAt + 5_000,
        )!!
        assertEquals(account.createdAt + 5_000, reauthorized.authorizedAt)
        assertEquals(account.createdAt, reauthorized.createdAt)
    }

    @Test
    fun `save refresh is idempotently reconciled with the exact encrypted result without rotation`() = runBlocking {
        val account = create("save-idempotent", enabled = true)
        val access = cipher.encrypt("access-idempotent")
        val first = repository.saveRefreshed(
            accountId = account.id,
            accessToken = access,
            expiresAt = account.createdAt + 3_600_000,
            tokenType = "Bearer",
            scope = "scope-a",
            expectedRefreshCiphertext = account.refreshTokenCiphertext,
            expectedAccount = account,
            now = account.createdAt + 1_000,
        )!!
        val second = repository.saveRefreshed(
            accountId = account.id,
            accessToken = access,
            expiresAt = first.accessTokenExpiresAt!!,
            tokenType = first.tokenType,
            scope = first.scope,
            expectedRefreshCiphertext = account.refreshTokenCiphertext,
            expectedAccount = account,
            now = account.createdAt + 2_000,
        )!!

        assertEquals(access.ciphertext, second.accessTokenCiphertext)
        assertEquals(access.nonce, second.accessTokenNonce)
        assertEquals(account.refreshTokenCiphertext, second.refreshTokenCiphertext)
        assertEquals(first.updatedAt, second.updatedAt, "reconciliation must not apply the write twice")
    }

    @Test
    fun `exact result reconciliation rejects a stale provider authorization or proxy snapshot`() = runBlocking {
        val account = create("save-snapshot", enabled = true)
        val access = cipher.encrypt("access-snapshot")
        val rotated = cipher.encrypt("refresh-snapshot")
        val saved = repository.saveRefreshed(
            accountId = account.id,
            accessToken = access,
            expiresAt = account.createdAt + 3_600_000,
            tokenType = "Bearer",
            scope = null,
            rotatedRefreshToken = rotated,
            expectedRefreshCiphertext = account.refreshTokenCiphertext,
            expectedAccount = account,
            now = account.createdAt + 1_000,
        )!!
        val savedExpiry = checkNotNull(saved.accessTokenExpiresAt)
        val reconciled = repository.saveRefreshed(
            accountId = account.id,
            accessToken = access,
            expiresAt = savedExpiry,
            tokenType = saved.tokenType,
            scope = saved.scope,
            rotatedRefreshToken = rotated,
            expectedRefreshCiphertext = account.refreshTokenCiphertext,
            expectedAccount = account,
            now = account.createdAt + 2_000,
        )!!
        assertEquals(saved.accessTokenCiphertext, reconciled.accessTokenCiphertext)
        assertEquals(saved.accessTokenNonce, reconciled.accessTokenNonce)
        assertEquals(saved.accessTokenKeyVersion, reconciled.accessTokenKeyVersion)
        assertEquals(saved.refreshTokenCiphertext, reconciled.refreshTokenCiphertext)
        assertEquals(saved.refreshTokenNonce, reconciled.refreshTokenNonce)
        assertEquals(saved.refreshTokenKeyVersion, reconciled.refreshTokenKeyVersion)
        assertEquals(saved.updatedAt, reconciled.updatedAt, "rotation reconciliation must not apply the write twice")

        val staleSnapshots = listOf(
            "account identity" to account.copy(id = UUID.randomUUID().toString()),
            "provider" to account.copy(provider = OAuthProvider.CHATGPT),
            "authorization time" to account.copy(authorizedAt = account.authorizedAt + 1),
            "proxy" to account.copy(proxyUrl = "http://127.0.0.1:8081"),
        )
        for ((field, expected) in staleSnapshots) {
            assertNull(
                repository.saveRefreshed(
                    accountId = account.id,
                    accessToken = access,
                    expiresAt = savedExpiry,
                    tokenType = saved.tokenType,
                    scope = saved.scope,
                    rotatedRefreshToken = rotated,
                    expectedAccount = expected,
                    now = account.createdAt + 3_000,
                ),
                "$field must be verified even when the exact encrypted result is stored",
            )
        }
        assertEquals(saved, repository.find(account.id), "rejected reconciliation must not change the row")

        repository.updateSettings(account.id, proxyUrl = "http://127.0.0.1:8080")
        assertNull(repository.saveRefreshed(
            accountId = account.id,
            accessToken = access,
            expiresAt = savedExpiry,
            tokenType = saved.tokenType,
            scope = saved.scope,
            rotatedRefreshToken = rotated,
            expectedAccount = account,
            now = account.createdAt + 4_000,
        ))
    }

    @Test
    fun `reauth required force refresh exact retry succeeds but administrator reauthorization rejects it`() = runBlocking {
        val account = create("save-reauth-force", enabled = true)
        repository.recordFailure(
            account.id,
            errorCode = "invalid_grant",
            terminal = true,
            now = account.createdAt + 1_000,
        )
        val expected = repository.find(account.id)!!
        assertEquals(OAuthAuthState.REAUTH_REQUIRED, expected.authState)

        val access = cipher.encrypt("forced-access")
        val rotated = cipher.encrypt("forced-refresh")
        val saved = repository.saveRefreshed(
            accountId = account.id,
            accessToken = access,
            expiresAt = account.createdAt + 3_600_000,
            tokenType = "Bearer",
            scope = "forced-scope",
            rotatedRefreshToken = rotated,
            expectedRefreshCiphertext = expected.refreshTokenCiphertext,
            expectedAccount = expected,
            now = account.createdAt + 2_000,
        )!!
        assertEquals(OAuthAuthState.ACTIVE, saved.authState)

        val retried = repository.saveRefreshed(
            accountId = account.id,
            accessToken = access,
            expiresAt = checkNotNull(saved.accessTokenExpiresAt),
            tokenType = saved.tokenType,
            scope = saved.scope,
            rotatedRefreshToken = rotated,
            expectedRefreshCiphertext = expected.refreshTokenCiphertext,
            expectedAccount = expected,
            now = account.createdAt + 3_000,
        )!!
        assertEquals(saved, retried, "a lost success response must reconcile the exact forced-refresh result")

        val administratorRefresh = cipher.encrypt("administrator-refresh")
        val reauthorized = repository.updateCredentials(
            account.id,
            OAuthAccountCredentialUpdate(
                provider = expected.provider,
                refreshToken = administratorRefresh,
            ),
            now = account.createdAt + 4_000,
        )!!
        assertEquals(administratorRefresh.ciphertext, reauthorized.refreshTokenCiphertext)
        assertNull(repository.saveRefreshed(
            accountId = account.id,
            accessToken = access,
            expiresAt = checkNotNull(saved.accessTokenExpiresAt),
            tokenType = saved.tokenType,
            scope = saved.scope,
            rotatedRefreshToken = rotated,
            expectedRefreshCiphertext = expected.refreshTokenCiphertext,
            expectedAccount = expected,
            now = account.createdAt + 5_000,
        ), "an old forced-refresh result must not cross administrator reauthorization")
        assertEquals(reauthorized, repository.find(account.id))
    }

    @Test
    fun `proxy-only change preserves rotation but clears stale access and reconciles exact retry`() = runBlocking {
        val created = create("proxy-rotation", enabled = true)
        val original = repository.updateCredentials(
            created.id,
            OAuthAccountCredentialUpdate(
                provider = created.provider,
                accessToken = cipher.encrypt("old-egress-access"),
                accessTokenExpiresAt = created.createdAt + 3_600_000,
                scope = "original-scope",
            ),
            now = created.createdAt + 500,
        )!!
        val rotated = cipher.encrypt("rotated-after-proxy-change")
        val staleEgressAccess = cipher.encrypt("new-access-from-old-egress")
        repository.updateSettings(original.id, proxyUrl = "http://127.0.0.1:8080", now = original.createdAt + 1_000)

        val saved = repository.saveRefreshed(
            accountId = original.id,
            accessToken = staleEgressAccess,
            expiresAt = original.createdAt + 3_600_000,
            tokenType = original.tokenType,
            scope = original.scope,
            rotatedRefreshToken = rotated,
            expectedRefreshCiphertext = original.refreshTokenCiphertext,
            expectedAccount = original,
            now = original.createdAt + 2_000,
        )!!
        assertEquals("http://127.0.0.1:8080", saved.proxyUrl)
        assertEquals(rotated.ciphertext, saved.refreshTokenCiphertext)
        assertEquals(rotated.nonce, saved.refreshTokenNonce)
        assertEquals(rotated.keyVersion, saved.refreshTokenKeyVersion)
        assertFalse(saved.hasAccessToken, "old-egress access must never escape the fallback")
        assertNull(saved.accessTokenCiphertext)
        assertNull(saved.accessTokenNonce)
        assertNull(saved.accessTokenKeyVersion)
        assertNull(saved.accessTokenExpiresAt)

        val retried = repository.saveRefreshed(
            accountId = original.id,
            accessToken = staleEgressAccess,
            expiresAt = original.createdAt + 3_600_000,
            tokenType = original.tokenType,
            scope = original.scope,
            rotatedRefreshToken = rotated,
            expectedRefreshCiphertext = original.refreshTokenCiphertext,
            expectedAccount = original,
            now = original.createdAt + 3_000,
        )
        assertEquals(saved, retried, "an unknown commit must reconcile without restoring stale access")

        val replacement = cipher.encrypt("administrator-refresh-after-fallback")
        val reauthorized = repository.updateCredentials(
            original.id,
            OAuthAccountCredentialUpdate(provider = original.provider, refreshToken = replacement),
            now = original.createdAt + 4_000,
        )!!
        assertNull(
            repository.saveRefreshed(
                accountId = original.id,
                accessToken = staleEgressAccess,
                expiresAt = original.createdAt + 3_600_000,
                tokenType = original.tokenType,
                scope = original.scope,
                rotatedRefreshToken = rotated,
                expectedRefreshCiphertext = original.refreshTokenCiphertext,
                expectedAccount = original,
                now = original.createdAt + 5_000,
            ),
            "a true authorization replacement must reject the old refresh result",
        )
        assertEquals(reauthorized, repository.find(original.id))
    }

    @Test
    fun `old refresh save is rejected after administrator changes access or proxy`() = runBlocking {
        val accessAccount = create("save-access-replaced", enabled = true)
        val access = cipher.encrypt("old-access-result")
        val adminAccess = cipher.encrypt("admin-access")
        repository.updateCredentials(
            accessAccount.id,
            OAuthAccountCredentialUpdate(
                provider = accessAccount.provider,
                accessToken = adminAccess,
                accessTokenExpiresAt = accessAccount.createdAt + 7_200_000,
            ),
            now = accessAccount.createdAt + 1_000,
        )
        assertNull(repository.saveRefreshed(
            accountId = accessAccount.id,
            accessToken = access,
            expiresAt = accessAccount.createdAt + 3_600_000,
            tokenType = "Bearer",
            scope = null,
            expectedRefreshCiphertext = accessAccount.refreshTokenCiphertext,
            expectedAccount = accessAccount,
            now = accessAccount.createdAt + 2_000,
        ))
        assertEquals(adminAccess.ciphertext, repository.find(accessAccount.id)!!.accessTokenCiphertext)

        val proxyAccount = create("save-proxy-replaced", enabled = true)
        val proxyAccess = cipher.encrypt("proxy-old-access")
        repository.updateSettings(proxyAccount.id, proxyUrl = "http://127.0.0.1:8080")
        assertNull(repository.saveRefreshed(
            accountId = proxyAccount.id,
            accessToken = proxyAccess,
            expiresAt = proxyAccount.createdAt + 3_600_000,
            tokenType = "Bearer",
            scope = null,
            expectedRefreshCiphertext = proxyAccount.refreshTokenCiphertext,
            expectedAccount = proxyAccount,
            now = proxyAccount.createdAt + 2_000,
        ))
        assertEquals("http://127.0.0.1:8080", repository.find(proxyAccount.id)!!.proxyUrl)
    }

    @Test
    fun `old refresh save is rejected after administrator reauthorizes`() = runBlocking {
        val account = create("save-reauthorized", enabled = true)
        val oldAccess = cipher.encrypt("old-access")
        val newRefresh = cipher.encrypt("admin-refresh")
        val reauthorized = repository.updateCredentials(
            account.id,
            OAuthAccountCredentialUpdate(
                provider = account.provider,
                refreshToken = newRefresh,
            ),
            now = account.createdAt + 1_000,
        )!!

        assertNull(repository.saveRefreshed(
            accountId = account.id,
            accessToken = oldAccess,
            expiresAt = account.createdAt + 3_600_000,
            tokenType = "Bearer",
            scope = null,
            expectedRefreshCiphertext = account.refreshTokenCiphertext,
            expectedAccount = account,
            now = account.createdAt + 2_000,
        ))
        assertEquals(newRefresh.ciphertext, reauthorized.refreshTokenCiphertext)
        assertNull(repository.find(account.id)!!.accessTokenCiphertext)
    }

    @Test
    fun `save refresh fences every original credential and authorization snapshot field`() = runBlocking {
        val original = create("save-cas", enabled = true)
        val account = repository.updateCredentials(
            original.id,
            OAuthAccountCredentialUpdate(
                provider = original.provider,
                accessToken = cipher.encrypt("cas-original-access"),
                accessTokenExpiresAt = original.createdAt + 3_600_000,
                tokenType = "Bearer",
                scope = "original-scope",
                proxyUrl = "http://127.0.0.1:8080",
            ),
            now = original.createdAt + 1_000,
        )!!
        val access = cipher.encrypt("cas-new-access")
        val staleSnapshots = listOf(
            "account identity" to account.copy(id = UUID.randomUUID().toString()),
            "provider" to account.copy(provider = OAuthProvider.CHATGPT),
            "authorization time" to account.copy(authorizedAt = account.authorizedAt + 1),
            "authorization state" to account.copy(authState = OAuthAuthState.REAUTH_REQUIRED),
            "proxy" to account.copy(proxyUrl = "http://127.0.0.1:8081"),
            "refresh ciphertext" to account.copy(refreshTokenCiphertext = account.refreshTokenCiphertext + "-stale"),
            "refresh nonce" to account.copy(refreshTokenNonce = account.refreshTokenNonce + "-stale"),
            "refresh key version" to account.copy(refreshTokenKeyVersion = account.refreshTokenKeyVersion + 1),
            "access ciphertext" to account.copy(accessTokenCiphertext = checkNotNull(account.accessTokenCiphertext) + "-stale"),
            "access nonce" to account.copy(accessTokenNonce = checkNotNull(account.accessTokenNonce) + "-stale"),
            "access key version" to account.copy(accessTokenKeyVersion = checkNotNull(account.accessTokenKeyVersion) + 1),
            "access expiry" to account.copy(accessTokenExpiresAt = checkNotNull(account.accessTokenExpiresAt) + 1),
            "token type" to account.copy(tokenType = "Other"),
            "scope" to account.copy(scope = "stale-scope"),
        )

        for ((field, expected) in staleSnapshots) {
            assertNull(
                repository.saveRefreshed(
                    accountId = account.id,
                    accessToken = access,
                    expiresAt = account.createdAt + 7_200_000,
                    tokenType = "Bearer",
                    scope = "new-scope",
                    expectedRefreshCiphertext = account.refreshTokenCiphertext,
                    expectedAccount = expected,
                    now = account.createdAt + 2_000,
                ),
                "$field must remain part of the original snapshot CAS",
            )
        }
        assertEquals(account, repository.find(account.id), "rejected saves must leave the original row unchanged")
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
