package dev.soven.prismllm.oauth

import com.sun.net.httpserver.HttpServer
import dev.soven.prismllm.channel.domain.ChannelRecord
import dev.soven.prismllm.channel.persistence.ChannelRepository
import dev.soven.prismllm.channel.persistence.NewChannelKey
import dev.soven.prismllm.config.GatewayConfig
import dev.soven.prismllm.config.OAuthConfig
import dev.soven.prismllm.db.DatabaseFactory
import dev.soven.prismllm.db.DiagnosticPreparationDeadline
import dev.soven.prismllm.oauth.domain.OAuthAccountRecord
import dev.soven.prismllm.oauth.domain.OAuthAuthState
import dev.soven.prismllm.oauth.domain.OAuthProviderConfig
import dev.soven.prismllm.oauth.infra.ChatGptOAuthProvider
import dev.soven.prismllm.oauth.infra.OAuthRefreshException
import dev.soven.prismllm.oauth.infra.OAuthTokenProvider
import dev.soven.prismllm.oauth.infra.OAuthTokenResponse
import dev.soven.prismllm.oauth.infra.OAuthTokenProviderRegistry
import dev.soven.prismllm.oauth.infra.isProxyHostAllowed
import dev.soven.prismllm.oauth.infra.postTokenRequest
import dev.soven.prismllm.oauth.infra.proxyAddress
import dev.soven.prismllm.oauth.persistence.NewOAuthAccount
import dev.soven.prismllm.oauth.persistence.OAuthAccountCredentialUpdate
import dev.soven.prismllm.oauth.persistence.OAuthAccountRepository
import dev.soven.prismllm.repo.OAuthProviderChannelLookup
import dev.soven.prismllm.shared.crypto.CredentialCipher
import dev.soven.prismllm.shared.domain.OAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.testcontainers.containers.PostgreSQLContainer
import java.net.InetSocketAddress
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("component")
@Timeout(60)
class OAuthTokenServiceTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var factory: DatabaseFactory
    private lateinit var channels: ChannelRepository
    private lateinit var accounts: OAuthAccountRepository
    private lateinit var cipher: CredentialCipher
    private lateinit var server: HttpServer
    private val tokenRequests = AtomicInteger()
    private val lastRequestBody = AtomicReference<String>()
    private val beforeReply = AtomicReference<(() -> Unit)?>(null)
    private val responseBodyStarted = AtomicReference<CompletableDeferred<Unit>?>(null)
    private val finishResponseBody = AtomicReference<CompletableDeferred<Unit>?>(null)
    private var reply: () -> Pair<Int, String> = { 200 to SUCCESS }

    @BeforeAll
    fun start() {
        postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }
        factory = DatabaseFactory(
            GatewayConfig.fromEnv(
                mapOf(
                    "DATABASE_URL" to postgres.jdbcUrl,
                    "DATABASE_USER" to postgres.username,
                    "DATABASE_PASSWORD" to postgres.password,
                    "GATEWAY_MASTER_KEY" to Base64.getEncoder().encodeToString(ByteArray(32) { 7 }),
                    "ADMIN_PASSWORD" to "test-password",
                ),
            ),
        )
        factory.migrate()
        cipher = CredentialCipher(ByteArray(32) { 7 })
        channels = ChannelRepository(factory.database, cipher)
        accounts = OAuthAccountRepository(factory.database, OAuthProviderChannelLookup())
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/token") { exchange ->
            tokenRequests.incrementAndGet()
            lastRequestBody.set(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            beforeReply.get()?.invoke()
            val (status, body) = reply()
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            responseBodyStarted.get()?.complete(Unit)
            finishResponseBody.get()?.let { finish ->
                runBlocking { withTimeout(10_000) { finish.await() } }
            }
            runCatching { exchange.responseBody.use { it.write(bytes) } }
            exchange.close()
        }
        server.start()
    }

    @AfterAll
    fun stop() {
        if (::server.isInitialized) server.stop(0)
        if (::factory.isInitialized) factory.close()
        if (::postgres.isInitialized) postgres.stop()
    }

    @BeforeEach
    fun reset() {
        tokenRequests.set(0)
        lastRequestBody.set(null)
        beforeReply.set(null)
        responseBodyStarted.set(null)
        finishResponseBody.set(null)
        reply = { 200 to SUCCESS }
    }

    @Test
    fun permanentSaveFailureRetainsRotationWithoutHoldingActiveTaskCapacity() = runBlocking {
        val accountId = createAccount("retained-permanent", accountEmail = email("retained-permanent"))
        val clock = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val service = service(now = clock::get)
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val function = "reject_refresh_$suffix"
        val trigger = "reject_refresh_trigger_$suffix"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE FUNCTION $function() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id = '$accountId' AND NEW.access_token_ciphertext IS NOT NULL THEN RAISE EXCEPTION 'synthetic write permission failure' USING ERRCODE = '42501'; END IF; RETURN NEW; END $$")
                statement.execute("CREATE TRIGGER $trigger BEFORE UPDATE ON oauth_accounts FOR EACH ROW EXECUTE FUNCTION $function()")
            }
        }
        try {
            val failure = assertFailsWith<OAuthTokenUnavailableException> { withTimeout(5_000) { service.accessToken(accountId) } }
            assertEquals("refresh_save_pending", failure.errorCode)
            withTimeout(5_000) {
                while (true) {
                    val monitor = OAuthTokenService::class.java.getDeclaredField("refreshLifecycleLock").apply { isAccessible = true }.get(service)
                    val finished = synchronized(monitor) {
                        (OAuthTokenService::class.java.getDeclaredField("refreshTasks").apply { isAccessible = true }.get(service) as Map<*, *>).isEmpty()
                    }
                    if (finished) break
                    delay(1)
                }
            }
            assertEquals(1, tokenRequests.get())
        } finally {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP TRIGGER IF EXISTS $trigger ON oauth_accounts")
                    statement.execute("DROP FUNCTION IF EXISTS $function()")
                }
            }
        }
        try {
            clock.addAndGet(3_600_001)
            val freshAccess = UUID.randomUUID().toString()
            val freshRefresh = UUID.randomUUID().toString()
            reply = { 200 to kotlinx.serialization.json.buildJsonObject {
                put("access_token", kotlinx.serialization.json.JsonPrimitive(freshAccess))
                put("refresh_token", kotlinx.serialization.json.JsonPrimitive(freshRefresh))
                put("expires_in", kotlinx.serialization.json.JsonPrimitive(3_600))
            }.toString() }
            val fresh = withTimeout(5_000) { service.accessToken(accountId) }!!
            assertEquals(freshAccess, fresh.accessToken)
            assertTrue(fresh.expiresAt > clock.get())
            assertEquals(2, tokenRequests.get(), "expired recovery saves rotation before one fresh exchange")
            assertTrue(lastRequestBody.get().contains("\"refresh_token\":\"rt-2\""))
            val context = accounts.loadRefreshContext(accountId)!!
            assertEquals(freshRefresh, cipher.decrypt(context.refreshTokenCiphertext, context.refreshTokenNonce))
        } finally {
            assertTrue(service.drainRefreshes())
        }
    }

    @Test
    fun deletedAccountsReleaseAnActuallyRetainedRotation() = runBlocking {
        val id = createAccount("orphan-retained", accountEmail = email("orphan-retained"))
        val service = service()
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val function = "orphan_save_$suffix"
        val trigger = "orphan_save_trigger_$suffix"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE FUNCTION $function() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id = '$id' AND NEW.access_token_ciphertext IS NOT NULL THEN RAISE EXCEPTION 'synthetic write failure' USING ERRCODE = '42501'; END IF; RETURN NEW; END $$")
                statement.execute("CREATE TRIGGER $trigger BEFORE UPDATE ON oauth_accounts FOR EACH ROW EXECUTE FUNCTION $function()")
            }
        }
        try {
            val failure = assertFailsWith<OAuthTokenUnavailableException> { withTimeout(5_000) { service.accessToken(id) } }
            assertEquals("refresh_save_pending", failure.errorCode)
            val monitor = OAuthTokenService::class.java.getDeclaredField("refreshLifecycleLock").apply { isAccessible = true }.get(service)
            val pendingField = OAuthTokenService::class.java.getDeclaredField("pendingRefreshes").apply { isAccessible = true }
            assertTrue(synchronized(monitor) { (pendingField.get(service) as Map<*, *>).containsKey(id) })
            assertTrue(accounts.delete(id))
            assertNull(service.accessToken(id))
            assertTrue(synchronized(monitor) { (pendingField.get(service) as Map<*, *>).isEmpty() })
            assertEquals(1, tokenRequests.get())
        } finally {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP TRIGGER IF EXISTS $trigger ON oauth_accounts")
                    statement.execute("DROP FUNCTION IF EXISTS $function()")
                }
            }
            assertTrue(service.drainRefreshes())
        }
    }

    @Test
    fun shutdownFlushesRetainedRotationAfterDatabaseWriteRecovery() = runBlocking {
        val id = createAccount("shutdown-retained", accountEmail = email("shutdown-retained"))
        val service = service()
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val function = "shutdown_save_$suffix"
        val trigger = "shutdown_save_trigger_$suffix"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE FUNCTION $function() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id = '$id' AND NEW.access_token_ciphertext IS NOT NULL THEN RAISE EXCEPTION 'synthetic write failure' USING ERRCODE = '42501'; END IF; RETURN NEW; END $$")
                statement.execute("CREATE TRIGGER $trigger BEFORE UPDATE ON oauth_accounts FOR EACH ROW EXECUTE FUNCTION $function()")
            }
        }
        try {
            val failure = assertFailsWith<OAuthTokenUnavailableException> { withTimeout(5_000) { service.accessToken(id) } }
            assertEquals("refresh_save_pending", failure.errorCode)
            withTimeout(5_000) {
                val monitor = OAuthTokenService::class.java.getDeclaredField("refreshLifecycleLock").apply { isAccessible = true }.get(service)
                val tasks = OAuthTokenService::class.java.getDeclaredField("refreshTasks").apply { isAccessible = true }
                while (synchronized(monitor) { (tasks.get(service) as Map<*, *>).isNotEmpty() }) delay(1)
            }
        } finally {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP TRIGGER IF EXISTS $trigger ON oauth_accounts")
                    statement.execute("DROP FUNCTION IF EXISTS $function()")
                }
            }
        }
        assertTrue(withTimeout(10_000) { service.drainRefreshes() })
        val saved = accounts.loadRefreshContext(id)!!
        assertEquals("rt-2", cipher.decrypt(saved.refreshTokenCiphertext, saved.refreshTokenNonce))
        assertEquals(1, tokenRequests.get(), "shutdown must persist the retained response without another exchange")
    }

    @Test
    fun terminalFailureFromOldExchangeCannotInvalidateAccessOnlyOrProxyEdits() = runBlocking {
        for (edit in listOf("access", "proxy")) {
            val id = createAccount("failure-snapshot-$edit", accountEmail = email("failure-$edit"))
            val service = service()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            beforeReply.set { entered.complete(Unit); runBlocking { withTimeout(5_000) { release.await() } } }
            reply = { 400 to """{"error":"invalid_grant"}""" }
            try {
                val call = async { runCatching { service.accessToken(id) } }
                withTimeout(5_000) { entered.await() }
                if (edit == "proxy") accounts.updateSettings(id, proxyUrl = "http://127.0.0.1:8181")
                else accounts.updateCredentials(id, OAuthAccountCredentialUpdate(
                    provider = OAuthProvider.CHATGPT,
                    accessToken = cipher.encrypt(UUID.randomUUID().toString()),
                    accessTokenExpiresAt = System.currentTimeMillis() + 3_600_000,
                ))
                release.complete(Unit)
                val failure = withTimeout(5_000) { call.await() }.exceptionOrNull()
                assertEquals("credential_replaced", assertIs<OAuthTokenUnavailableException>(failure).errorCode)
                val saved = accounts.find(id)!!
                assertEquals(OAuthAuthState.ACTIVE, saved.authState)
                assertEquals(0, saved.refreshFailureCount)
                assertNull(saved.lastRefreshError)
            } finally {
                release.complete(Unit)
                assertTrue(service.drainRefreshes())
                beforeReply.set(null)
            }
        }
    }

    @Test
    fun tokenRequestDeadlineCoversAStalledResponseBody() = runBlocking {
        val bodyStarted = CompletableDeferred<Unit>()
        val finishBody = CompletableDeferred<Unit>()
        responseBodyStarted.set(bodyStarted)
        finishResponseBody.set(finishBody)

        try {
            val request = async(Dispatchers.IO) {
                runCatching {
                    postTokenRequest(
                        endpoint = "http://127.0.0.1:${server.address.port}/token",
                        contentType = "application/json",
                        body = "{}",
                        proxyUrl = null,
                        timeoutMs = 500,
                    )
                }
            }
            withTimeout(5_000) { bodyStarted.await() }
            val failure = assertIs<OAuthRefreshException>(request.await().exceptionOrNull())
            assertEquals("timeout", failure.errorCode)
        } finally {
            finishBody.complete(Unit)
            responseBodyStarted.set(null)
            finishResponseBody.set(null)
        }
    }

    @Test
    fun shutdownRetainsAndSavesAnExchangeThatReturnsAfterSupervisorCancellation() = runBlocking {
        val accountId = createAccount("shutdown-handoff", accountEmail = email("shutdown-handoff"))
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<OAuthTokenResponse>()
        val service = serviceWithProvider(object : OAuthTokenProvider {
            override val provider = OAuthProvider.CHATGPT
            override suspend fun refresh(refreshToken: String, proxyUrl: String?): OAuthTokenResponse {
                entered.complete(Unit)
                return response.await()
            }
        }, requestTimeoutMs = 5_000)
        val request = async { runCatching { service.accessToken(accountId) } }
        try {
            withTimeout(5_000) { entered.await() }
            val supervisor = OAuthTokenService::class.java.getDeclaredField("refreshSupervisor").apply { isAccessible = true }
                .get(service) as kotlinx.coroutines.Job
            val drain = async { service.drainRefreshes(timeoutMs = 1) }
            withTimeout(5_000) { while (!supervisor.isCancelled) delay(1) }
            response.complete(OAuthTokenResponse("shutdown-rotated-access", "shutdown-rotated-refresh"))
            assertFalse(withTimeout(7_000) { drain.await() })
            withTimeout(5_000) { request.await() }
            val saved = accounts.find(accountId)!!
            assertEquals("shutdown-rotated-access", cipher.decrypt(saved.accessTokenCiphertext!!, saved.accessTokenNonce!!))
            val context = accounts.loadRefreshContext(accountId)!!
            assertEquals("shutdown-rotated-refresh", cipher.decrypt(context.refreshTokenCiphertext, context.refreshTokenNonce))
        } finally {
            response.complete(OAuthTokenResponse("shutdown-cleanup-access", "shutdown-cleanup-refresh"))
            request.cancelAndJoin()
            assertTrue(service.drainRefreshes())
        }
    }

    @Test
    fun serviceDeadlineCancelsProviderWithoutItsOwnTimerAndReleasesExchangePermit() = runBlocking {
        val accountId = createAccount("service-timeout", accountEmail = email("service-timeout"))
        val original = accounts.find(accountId)!!
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val release = CompletableDeferred<OAuthTokenResponse>()
        val service = serviceWithProvider(object : OAuthTokenProvider {
            override val provider = OAuthProvider.CHATGPT

            override suspend fun refresh(refreshToken: String, proxyUrl: String?): OAuthTokenResponse {
                entered.complete(Unit)
                try {
                    return release.await()
                } catch (cause: CancellationException) {
                    cancelled.complete(Unit)
                    throw cause
                }
            }
        }, requestTimeoutMs = 250)
        val request = async { runCatching { service.accessToken(accountId) } }
        try {
            withTimeout(5_000) { entered.await() }
            val failure = assertIs<OAuthTokenUnavailableException>(withTimeout(5_000) { request.await() }.exceptionOrNull())
            assertEquals(OAuthUnavailableReason.REFRESH_FAILED, failure.reason)
            assertEquals("timeout", failure.errorCode)
            withTimeout(5_000) { cancelled.await() }
            val account = accounts.find(accountId)!!
            assertEquals(OAuthAuthState.ACTIVE, account.authState)
            assertEquals(1, account.refreshFailureCount)
            assertEquals("timeout", account.lastRefreshError)
            assertFalse(account.hasAccessToken)
            assertEquals(original.refreshTokenCiphertext, account.refreshTokenCiphertext)
            val field = OAuthTokenService::class.java.getDeclaredField("refreshPermits").apply { isAccessible = true }
            assertEquals(4, (field.get(service) as kotlinx.coroutines.sync.Semaphore).availablePermits)
        } finally {
            release.complete(OAuthTokenResponse("test-cleanup-access", "test-cleanup-refresh"))
            request.cancelAndJoin()
            assertTrue(service.drainRefreshes())
        }
    }

    @Test
    fun exchangePermitIsAvailableWhileOtherAccountsRetryBlockedSaves() = runBlocking {
        val providerCalls = AtomicInteger()
        val fifthExchange = CompletableDeferred<Unit>()
        val provider = object : OAuthTokenProvider {
            override val provider = OAuthProvider.CHATGPT

            override suspend fun refresh(refreshToken: String, proxyUrl: String?): OAuthTokenResponse {
                if (providerCalls.incrementAndGet() == 5) fifthExchange.complete(Unit)
                return OAuthTokenResponse(
                    accessToken = "access-$refreshToken",
                    refreshToken = "rotated-$refreshToken",
                )
            }
        }
        val service = serviceWithProvider(provider)
        val accountIds = mutableListOf<String>()
        val stripes = mutableSetOf<kotlinx.coroutines.sync.Mutex>()
        while (accountIds.size < 5) {
            val accountId = createAccount("permit-${UUID.randomUUID()}", accountEmail = email("permit"))
            val stripe = accountMutex(service, accountId)
            if (stripes.add(stripe)) accountIds += accountId
        }
        val saveBlockedIds = accountIds.take(4)
        val calls = mutableListOf<kotlinx.coroutines.Deferred<dev.soven.prismllm.oauth.domain.OAuthAccessToken?>>()

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { lock ->
            lock.autoCommit = false
            try {
                saveBlockedIds.forEach { accountId ->
                    lock.prepareStatement("select id from oauth_accounts where id = ? for update").use { statement ->
                        statement.setString(1, accountId)
                        statement.executeQuery().use { assertTrue(it.next()) }
                    }
                }
                saveBlockedIds.forEach { accountId ->
                    calls += async(Dispatchers.IO) { service.accessToken(accountId, forceRefresh = true) }
                }
                val blockerPid = lock.createStatement().use { statement ->
                    statement.executeQuery("select pg_backend_pid()").use { rows ->
                        assertTrue(rows.next())
                        rows.getInt(1)
                    }
                }
                awaitBlockedAccountSaveCount(blockerPid, expected = 4)

                calls += async(Dispatchers.IO) { service.accessToken(accountIds.last(), forceRefresh = true) }
                withTimeout(5_000) { fifthExchange.await() }
            } finally {
                lock.rollback()
            }
        }

        try {
            val results = calls.map { it.await() }
            assertEquals(5, providerCalls.get())
            assertTrue(results.all { it?.accessToken?.startsWith("access-permit-") == true })
        } finally {
            assertTrue(service.drainRefreshes())
        }
    }

    private suspend fun awaitBlockedAccountSaveCount(blockerPid: Int, expected: Int) = withTimeout(5_000) {
        while (true) {
            val count = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { observer ->
                observer.prepareStatement(
                    "select count(*) from pg_stat_activity where datname = current_database() " +
                        "and query ilike '%oauth_accounts%' and $blockerPid = any(pg_blocking_pids(pid)) " +
                        "and state = 'active' and wait_event_type = 'Lock'",
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        rows.getInt(1)
                    }
                }
            }
            if (count >= expected) return@withTimeout
            delay(10)
        }
        error("unreachable")
    }

    @Test
    fun diagnosticSnapshotRejectsExpiryTypeAndScopeOnlyChanges() = runBlocking {
        val id = createAccount("metadata-snapshot", accountEmail = email("metadata-snapshot"))
        val service = service()
        try {
            service.accessToken(id)
            val saved = accounts.find(id)!!
            val access = service.accessTokenForDiagnostic(saved, DiagnosticPreparationDeadline.start(1_000))
            assertTrue(access.matches(saved))
            for (changed in listOf(
                saved.copy(accessTokenExpiresAt = saved.accessTokenExpiresAt!! - 1),
                saved.copy(tokenType = "ChangedType"),
                saved.copy(scope = "changed-scope"),
            )) {
                assertFalse(access.matches(changed), "metadata-only credential changes invalidate the diagnostic snapshot")
                assertFalse(saved.matchesCredentialSnapshot(changed))
            }
        } finally {
            assertTrue(service.drainRefreshes())
        }
    }

    @Test
    fun everyWaiterKindRejectsReplacedCredentialsBeforeExchangeAfterPermitWaiting() = runBlocking {
        for (kind in listOf("ordinary", "forced", "manual", "diagnostic")) {
            val id = createAccount("queued-$kind-original", accountEmail = email("queued-$kind"))
            val calls = AtomicInteger()
            val service = serviceWithProvider(object : OAuthTokenProvider {
                override val provider = OAuthProvider.CHATGPT
                override suspend fun refresh(refreshToken: String, proxyUrl: String?): OAuthTokenResponse {
                    calls.incrementAndGet()
                    return OAuthTokenResponse("unexpected-access", "unexpected-rotation")
                }
            })
            val permits = OAuthTokenService::class.java.getDeclaredField("refreshPermits").apply { isAccessible = true }
                .get(service) as kotlinx.coroutines.sync.Semaphore
            repeat(4) { permits.acquire() }
            val original = accounts.find(id)!!
            var released = false
            val caller = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    when (kind) {
                        "manual" -> service.refreshNow(id)
                        "diagnostic" -> service.accessTokenForDiagnostic(original, DiagnosticPreparationDeadline.start(10_000))
                        else -> service.accessToken(id, forceRefresh = kind == "forced")
                    }
                }
            }
            try {
                awaitExchangePermitWait(service, id)
                val changed = assertNotNull(accounts.updateCredentials(id, OAuthAccountCredentialUpdate(
                    provider = original.provider, refreshToken = cipher.encrypt("queued-$kind-replacement"),
                )))
                repeat(4) { permits.release() }
                released = true
                val result = withTimeout(5_000) { caller.await() }
                if (kind == "manual") assertEquals("credential_replaced", assertIs<OAuthRefreshResult.Failed>(result.getOrThrow()).errorCode)
                else assertEquals("credential_replaced", assertIs<OAuthTokenUnavailableException>(result.exceptionOrNull()).errorCode)
                awaitRefreshTasksEnded(service)
                assertEquals(0, calls.get(), "A stale $kind waiter cannot authorize a replacement exchange")
                val unchanged = assertNotNull(accounts.find(id))
                assertTrue(changed.matchesCredentialSnapshot(unchanged))
                assertEquals(0, unchanged.refreshFailureCount)
                assertNull(unchanged.lastRefreshError)
            } finally {
                if (!released) repeat(4) { permits.release() }
                caller.cancelAndJoin()
                assertTrue(service.drainRefreshes())
            }
        }
    }

    @Test
    fun shutdownSaveFailureDoesNotDiscardAnotherAccountsOpportunityToPersistItsRotation() = runBlocking {
        val first = createAccount("drain-first", accountEmail = email("drain-first"))
        val second = createAccount("drain-second", accountEmail = email("drain-second"))
        val calls = AtomicInteger()
        val service = serviceWithProvider(object : OAuthTokenProvider {
            override val provider = OAuthProvider.CHATGPT
            override suspend fun refresh(refreshToken: String, proxyUrl: String?): OAuthTokenResponse {
                calls.incrementAndGet()
                return OAuthTokenResponse("access-$refreshToken", "rotated-$refreshToken")
            }
        })
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val function = "drain_rotation_$suffix"
        val trigger = "drain_rotation_trigger_$suffix"
        fun blockWrites(ids: List<String>, create: Boolean = false) {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    val selected = ids.joinToString(",") { "'$it'" }
                    statement.execute("CREATE OR REPLACE FUNCTION $function() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id IN ($selected) AND NEW.access_token_ciphertext IS NOT NULL THEN RAISE EXCEPTION 'synthetic independent save failure' USING ERRCODE = '42501'; END IF; RETURN NEW; END $$")
                    if (create) statement.execute("CREATE TRIGGER $trigger BEFORE UPDATE ON oauth_accounts FOR EACH ROW EXECUTE FUNCTION $function()")
                }
            }
        }
        blockWrites(listOf(first, second), create = true)
        val monitor = OAuthTokenService::class.java.getDeclaredField("refreshLifecycleLock").apply { isAccessible = true }.get(service)
        val pending = OAuthTokenService::class.java.getDeclaredField("pendingRefreshes").apply { isAccessible = true }
        try {
            for (id in listOf(first, second)) {
                val failure = assertFailsWith<OAuthTokenUnavailableException> { withTimeout(5_000) { service.accessToken(id) } }
                assertEquals("refresh_save_pending", failure.errorCode)
                awaitRefreshTasksEnded(service)
            }
            assertEquals(listOf(first, second), synchronized(monitor) { (pending.get(service) as Map<*, *>).keys.toList() })
            val firstPending = synchronized(monitor) { (pending.get(service) as Map<*, *>)[first] }
            assertNotNull(firstPending)
            blockWrites(listOf(first))
            assertFalse(withTimeout(10_000) { service.drainRefreshes(timeoutMs = 5_000) })
            val saved = assertNotNull(accounts.find(second))
            assertEquals("access-drain-second", cipher.decrypt(saved.accessTokenCiphertext!!, saved.accessTokenNonce!!))
            val context = assertNotNull(accounts.loadRefreshContext(second))
            assertEquals("rotated-drain-second", cipher.decrypt(context.refreshTokenCiphertext, context.refreshTokenNonce))
            assertFalse(accounts.find(first)!!.hasAccessToken)
            assertEquals(setOf(first), synchronized(monitor) { (pending.get(service) as Map<*, *>).keys.toSet() })
            assertTrue(synchronized(monitor) { (pending.get(service) as Map<*, *>)[first] === firstPending })
            assertEquals(2, calls.get(), "Shutdown saves retained rotations without a second provider exchange")
        } finally {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP TRIGGER IF EXISTS $trigger ON oauth_accounts")
                    statement.execute("DROP FUNCTION IF EXISTS $function()")
                }
            }
            assertTrue(withTimeout(10_000) { service.drainRefreshes() })
        }
        val recovered = assertNotNull(accounts.find(first))
        assertEquals("access-drain-first", cipher.decrypt(recovered.accessTokenCiphertext!!, recovered.accessTokenNonce!!))
        assertEquals(2, calls.get())
    }

    @Test
    fun ordinaryAndForcedWaitersRejectACompletedResultFromReplacedAuthorization() = runBlocking {
        val id = createAccount("late-result-old", accountEmail = email("late-result"))
        val service = service()
        try {
            service.accessToken(id)
            val old = accounts.find(id)!!
            val monitor = OAuthTokenService::class.java.getDeclaredField("refreshLifecycleLock").apply { isAccessible = true }.get(service)
            val tasks = OAuthTokenService::class.java.getDeclaredField("refreshTasks").apply { isAccessible = true }
                .get(service) as MutableMap<String, Any>
            withTimeout(5_000) { while (synchronized(monitor) { tasks.isNotEmpty() }) delay(1) }
            accounts.updateCredentials(id, OAuthAccountCredentialUpdate(
                provider = old.provider, refreshToken = cipher.encrypt("late-result-new"),
            ))
            val current = accounts.find(id)!!
            assertFalse(old.matchesCredentialSnapshot(current))

            val taskType = OAuthTokenService::class.java.declaredClasses.single { it.simpleName == "RefreshTask" }
            val outcomeType = OAuthTokenService::class.java.declaredClasses.single { it.simpleName == "RefreshOutcome" }
            val oldOutcome = outcomeType.getDeclaredConstructor(OAuthAccountRecord::class.java, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.newInstance(old, true)
            for (manual in listOf(false, true)) {
                // Represent the post-save, pre-completion window without a scheduler-dependent sleep.
                val task = taskType.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                val work = CompletableDeferred<Any>()
                taskType.getDeclaredField("work").apply { isAccessible = true }.set(task, work)
                synchronized(monitor) { tasks[id] = task }
                try {
                    val pending = async {
                        if (manual) service.refreshNow(id)
                        else runCatching { service.accessToken(id) }.exceptionOrNull()
                    }
                    val waiters = taskType.getDeclaredField("waiters").apply { isAccessible = true }
                    withTimeout(5_000) {
                        while (synchronized(monitor) { (waiters.get(task) as List<*>).isEmpty() }) delay(1)
                    }
                    work.complete(oldOutcome)
                    val result = withTimeout(5_000) { pending.await() }
                    if (manual) assertEquals("credential_replaced", assertIs<OAuthRefreshResult.Failed>(result).errorCode)
                    else assertEquals("credential_replaced", assertIs<OAuthTokenUnavailableException>(result).errorCode)
                    assertEquals(current, accounts.find(id))
                } finally {
                    work.complete(oldOutcome)
                    synchronized(monitor) { tasks.remove(id, task) }
                }
            }
            assertEquals(1, tokenRequests.get(), "joined stale outcomes must never start a new exchange")
        } finally {
            assertTrue(service.drainRefreshes())
        }
    }

    @Test
    fun fullPendingCapacityJoinsExistingWorkAndCoalescesPruning() = runBlocking {
        val id = createAccount("capacity-target", accountEmail = email("capacity-target"))
        val account = accounts.find(id)!!
        val scanStarted = CompletableDeferred<Unit>()
        val finishScan = CompletableDeferred<Unit>()
        val scanReads = AtomicInteger()
        val repository = object : OAuthAccountRepository(factory.database, OAuthProviderChannelLookup()) {
            override suspend fun find(id: String): OAuthAccountRecord? {
                if (!id.startsWith("pending-capacity-")) return super.find(id)
                scanReads.incrementAndGet()
                scanStarted.complete(Unit)
                withTimeout(10_000) { finishScan.await() }
                return null
            }
        }
        val service = OAuthTokenService(repository, cipher, OAuthTokenProviderRegistry(emptyList()))
        val monitor = OAuthTokenService::class.java.getDeclaredField("refreshLifecycleLock").apply { isAccessible = true }.get(service)
        val tasks = OAuthTokenService::class.java.getDeclaredField("refreshTasks").apply { isAccessible = true }.get(service) as MutableMap<String, Any>
        val retained = OAuthTokenService::class.java.getDeclaredField("pendingRefreshes").apply { isAccessible = true }.get(service) as MutableMap<String, Any>
        val taskType = OAuthTokenService::class.java.declaredClasses.single { it.simpleName == "RefreshTask" }
        val outcomeType = OAuthTokenService::class.java.declaredClasses.single { it.simpleName == "RefreshOutcome" }
        val pendingType = OAuthTokenService::class.java.declaredClasses.single { it.simpleName == "PendingRefresh" }
        val encrypted = cipher.encrypt("encrypted-capacity-fixture")
        val constructor = pendingType.declaredConstructors.single().apply { isAccessible = true }
        synchronized(monitor) {
            repeat(512) { index ->
                val pendingId = "pending-capacity-$index"
                val pendingAccount = account.copy(id = pendingId)
                val context = dev.soven.prismllm.oauth.persistence.OAuthRefreshContext(
                    pendingAccount, pendingAccount.refreshTokenCiphertext!!, pendingAccount.refreshTokenNonce!!,
                    pendingAccount.refreshTokenKeyVersion!!,
                )
                retained[pendingId] = constructor.newInstance(context, encrypted, encrypted,
                    System.currentTimeMillis() + 60_000, "Bearer", null)
            }
        }
        val first = async { runCatching { service.accessToken(id) } }
        try {
            withTimeout(5_000) { scanStarted.await() }
            val busy = withTimeout(2_000) { runCatching { service.accessToken(id) } }.exceptionOrNull()
            assertEquals("refresh_busy", assertIs<OAuthTokenUnavailableException>(busy).errorCode)
            assertEquals(1, scanReads.get(), "concurrent capacity calls share one database scan")

            val task = taskType.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            val work = CompletableDeferred<Any>()
            taskType.getDeclaredField("work").apply { isAccessible = true }.set(task, work)
            synchronized(monitor) { tasks[id] = task }
            try {
                val joining = async { runCatching { service.accessToken(id) } }
                val waiters = taskType.getDeclaredField("waiters").apply { isAccessible = true }
                withTimeout(2_000) {
                    while (synchronized(monitor) { (waiters.get(task) as List<*>).isEmpty() }) delay(1)
                }
                assertEquals(1, scanReads.get(), "existing same-account work is joined before any cleanup")
                val outcome = outcomeType.getDeclaredConstructor(OAuthAccountRecord::class.java, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.newInstance(account, true)
                work.complete(outcome)
                val failure = withTimeout(2_000) { joining.await() }.exceptionOrNull()
                assertEquals("credential_replaced", assertIs<OAuthTokenUnavailableException>(failure).errorCode)
            } finally {
                work.cancel()
                synchronized(monitor) { tasks.remove(id, task) }
            }
        } finally {
            finishScan.complete(Unit)
            first.cancelAndJoin()
            synchronized(monitor) { retained.clear() }
            assertTrue(service.drainRefreshes())
        }
    }

    @Test
    fun tokenSnapshotStaysPairedWithItsTokenAfterRefreshAndAdminReplacement() = runBlocking {
        val id = createAccount("snapshot-original", accountEmail = email("snapshot"))
        val original = accounts.find(id)!!
        val service = service()
        try {
            val acquired = assertNotNull(service.accessTokenWithSnapshot(id))
            assertEquals("at-1", acquired.token.accessToken)
            assertEquals("at-1", cipher.decrypt(acquired.account.accessTokenCiphertext!!, acquired.account.accessTokenNonce!!))
            assertTrue(acquired.account.refreshTokenCiphertext != original.refreshTokenCiphertext)
            accounts.updateCredentials(id, OAuthAccountCredentialUpdate(
                provider = acquired.account.provider,
                refreshToken = cipher.encrypt("admin-replacement-refresh"),
                accessToken = cipher.encrypt("admin-replacement-access"),
                accessTokenExpiresAt = System.currentTimeMillis() + 3_600_000,
            ))
            val changed = accounts.find(id)!!
            assertFalse(acquired.account.matchesCredentialSnapshot(changed))
            assertEquals("at-1", acquired.token.accessToken)
            assertEquals("at-1", cipher.decrypt(acquired.account.accessTokenCiphertext!!, acquired.account.accessTokenNonce!!))
        } finally {
            assertTrue(service.drainRefreshes())
        }
    }

    @Test
    fun staleAccountReadCannotDiscardNewerPendingRefreshAfterSaveFailure() = runBlocking {
        val id = createAccount("rt-stale-pending", accountEmail = email("stale-pending"))
        val firstReadStarted = CompletableDeferred<Unit>()
        val releaseFirstRead = CompletableDeferred<Unit>()
        val heldRead = AtomicReference<Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>?>(null)
        val repository = object : OAuthAccountRepository(factory.database, OAuthProviderChannelLookup()) {
            override suspend fun find(accountId: String): OAuthAccountRecord? {
                val account = super.find(accountId)
                val gate = if (accountId == id) heldRead.getAndSet(null) else null
                if (gate != null) {
                    gate.first.complete(Unit)
                    withTimeout(5_000) { gate.second.await() }
                }
                return account
            }
        }
        val providerCalls = AtomicInteger()
        val provider = object : OAuthTokenProvider {
            override val provider = OAuthProvider.CHATGPT

            override suspend fun refresh(refreshToken: String, proxyUrl: String?): OAuthTokenResponse =
                when (providerCalls.incrementAndGet()) {
                    1 -> {
                        assertEquals("rt-stale-pending", refreshToken)
                        OAuthTokenResponse("at-old", "rt-old-rotated")
                    }
                    2 -> {
                        assertEquals("rt-replaced-pending", refreshToken)
                        OAuthTokenResponse("at-new", "rt-new-rotated")
                    }
                    else -> error("pending recovery must reuse its rotation, not exchange again")
                }
        }
        val service = OAuthTokenService(
            repository = repository,
            cipher = cipher,
            providers = OAuthTokenProviderRegistry(listOf(provider)),
            config = OAuthConfig(refreshAheadMs = 900_000),
        )
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val function = "stale_pending_save_$suffix"
        val trigger = "stale_pending_trigger_$suffix"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE FUNCTION $function() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id = '$id' AND NEW.access_token_ciphertext IS NOT NULL THEN RAISE EXCEPTION 'synthetic write failure' USING ERRCODE = '42501'; END IF; RETURN NEW; END $$")
                statement.execute("CREATE TRIGGER $trigger BEFORE UPDATE ON oauth_accounts FOR EACH ROW EXECUTE FUNCTION $function()")
            }
        }
        val monitor = OAuthTokenService::class.java.getDeclaredField("refreshLifecycleLock").apply { isAccessible = true }
        val pendingField = OAuthTokenService::class.java.getDeclaredField("pendingRefreshes").apply { isAccessible = true }
        val tasksField = OAuthTokenService::class.java.getDeclaredField("refreshTasks").apply { isAccessible = true }
        var staleRead: kotlinx.coroutines.Deferred<Result<OAuthAccountAccess?>>? = null
        try {
            val firstFailure = assertFailsWith<OAuthTokenUnavailableException> {
                withTimeout(5_000) { service.accessToken(id) }
            }
            assertEquals("refresh_save_pending", firstFailure.errorCode)
            withTimeout(5_000) {
                while (synchronized(monitor.get(service)) { (tasksField.get(service) as Map<*, *>).isNotEmpty() }) delay(1)
            }
            val firstPending = synchronized(monitor.get(service)) { (pendingField.get(service) as Map<*, *>)[id] }
            assertNotNull(firstPending)

            // Capture the old account row, but suspend its return after accessTokenWithSnapshot
            // has already observed the first pending result and before it can inspect the map again.
            heldRead.set(firstReadStarted to releaseFirstRead)
            staleRead = async(Dispatchers.IO) { runCatching { service.accessTokenWithSnapshot(id) } }
            withTimeout(5_000) { firstReadStarted.await() }
            accounts.updateCredentials(
                id,
                OAuthAccountCredentialUpdate(
                    provider = OAuthProvider.CHATGPT,
                    refreshToken = cipher.encrypt("rt-replaced-pending"),
                ),
            )

            // Replacing credentials discards the first result. The next exchange then rotates the
            // replacement credential but cannot persist it, leaving a different pending result.
            val secondFailure = assertFailsWith<OAuthTokenUnavailableException> {
                withTimeout(5_000) { service.accessToken(id) }
            }
            assertEquals("refresh_save_pending", secondFailure.errorCode)
            val secondPending = synchronized(monitor.get(service)) { (pendingField.get(service) as Map<*, *>)[id] }
            assertNotNull(secondPending)
            assertTrue(secondPending !== firstPending, "the replacement refresh must own a new pending result")
            withTimeout(5_000) {
                while (synchronized(monitor.get(service)) { (tasksField.get(service) as Map<*, *>).isNotEmpty() }) {
                    delay(1)
                }
            }

            releaseFirstRead.complete(Unit)
            val staleFailure = withTimeout(5_000) { staleRead.await() }.exceptionOrNull()
            assertEquals("refresh_save_pending", assertIs<OAuthTokenUnavailableException>(staleFailure).errorCode)
            withTimeout(5_000) {
                while (synchronized(monitor.get(service)) { (tasksField.get(service) as Map<*, *>).isNotEmpty() }) delay(1)
            }
            assertTrue(
                synchronized(monitor.get(service)) { (pendingField.get(service) as Map<*, *>)[id] === secondPending },
                "the stale account snapshot must not discard the newer failed-save rotation",
            )

            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP TRIGGER $trigger ON oauth_accounts")
                    statement.execute("DROP FUNCTION $function()")
                }
            }
            val recovered = withTimeout(5_000) { service.accessToken(id) }
            assertEquals("at-new", assertNotNull(recovered).accessToken)
            assertEquals(2, providerCalls.get(), "recovery must persist the retained rotation without re-exchange")
            val context = accounts.loadRefreshContext(id)!!
            assertEquals("rt-new-rotated", cipher.decrypt(context.refreshTokenCiphertext, context.refreshTokenNonce))
        } finally {
            releaseFirstRead.complete(Unit)
            staleRead?.let { runCatching { withTimeout(5_000) { it.await() } } }
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP TRIGGER IF EXISTS $trigger ON oauth_accounts")
                    statement.execute("DROP FUNCTION IF EXISTS $function()")
                }
            }
            assertTrue(service.drainRefreshes())
        }
    }

    @Test
    fun refreshesCachesAndPersistsRotatedRefreshToken() = runBlocking {
        val keyId = createAccount("rt-1", accountEmail = email("cache"), accountPlan = "free")
        val service = service()

        val first = service.accessToken(keyId)
        assertEquals("at-1", first!!.accessToken)
        assertTrue(first.expiresAt > System.currentTimeMillis())
        assertEquals(1, tokenRequests.get())
        assertTrue(lastRequestBody.get().contains("\"grant_type\":\"refresh_token\""))
        assertTrue(lastRequestBody.get().contains("\"refresh_token\":\"rt-1\""))

        val context = accounts.loadRefreshContext(keyId)!!
        assertEquals("rt-2", cipher.decrypt(context.refreshTokenCiphertext, context.refreshTokenNonce))

        // 缓存命中时不再请求 token 端点。
        assertEquals("at-1", service.accessToken(keyId)!!.accessToken)
        assertEquals(1, tokenRequests.get())

        val account = accounts.find(keyId)!!
        assertEquals(OAuthAuthState.ACTIVE, account.authState)
        assertEquals(0, account.refreshFailureCount)
        assertNotNull(account.accessTokenCiphertext)
        assertEquals("free", account.accountPlan)
    }

    @Test
    fun expiredProviderResponsesAreBoundedAndPersistEveryRefreshRotation() = runBlocking {
        fun expiringProvider(
            accountId: String,
            expectedRefresh: AtomicReference<String>,
            calls: AtomicInteger,
            lastRotation: AtomicReference<String>,
        ) = object : OAuthTokenProvider {
            override val provider = OAuthProvider.CHATGPT

            override suspend fun refresh(refreshToken: String, proxyUrl: String?): OAuthTokenResponse {
                val expected = expectedRefresh.get()
                assertEquals(expected, refreshToken)
                val persisted = accounts.loadRefreshContext(accountId)!!
                assertEquals(
                    expected,
                    cipher.decrypt(persisted.refreshTokenCiphertext, persisted.refreshTokenNonce),
                    "each exchange must use the refresh rotation committed by the previous exchange",
                )
                calls.incrementAndGet()
                val rotation = UUID.randomUUID().toString()
                expectedRefresh.set(rotation)
                lastRotation.set(rotation)
                return OAuthTokenResponse(
                    accessToken = UUID.randomUUID().toString(),
                    refreshToken = rotation,
                    expiresInSeconds = 0,
                )
            }
        }

        val ordinaryRefresh = UUID.randomUUID().toString()
        val ordinaryExpected = AtomicReference(ordinaryRefresh)
        val ordinaryCalls = AtomicInteger()
        val ordinaryRotation = AtomicReference<String>()
        val ordinaryId = createAccount(ordinaryRefresh, accountEmail = email("expired-ordinary"))
        val ordinaryService = serviceWithProvider(
            expiringProvider(ordinaryId, ordinaryExpected, ordinaryCalls, ordinaryRotation),
        )
        val forcedRefresh = UUID.randomUUID().toString()
        val forcedExpected = AtomicReference(forcedRefresh)
        val forcedCalls = AtomicInteger()
        val forcedRotation = AtomicReference<String>()
        val forcedId = createAccount(forcedRefresh, accountEmail = email("expired-forced"))
        val forcedService = serviceWithProvider(
            expiringProvider(forcedId, forcedExpected, forcedCalls, forcedRotation),
        )
        val diagnosticRefresh = UUID.randomUUID().toString()
        val diagnosticExpected = AtomicReference(diagnosticRefresh)
        val diagnosticCalls = AtomicInteger()
        val diagnosticRotation = AtomicReference<String>()
        val diagnosticId = createAccount(diagnosticRefresh, accountEmail = email("expired-diagnostic"))
        val diagnosticService = serviceWithProvider(
            expiringProvider(diagnosticId, diagnosticExpected, diagnosticCalls, diagnosticRotation),
        )

        try {
            val ordinaryFailure = assertFailsWith<OAuthTokenUnavailableException> {
                withTimeout(5_000) { ordinaryService.accessToken(ordinaryId) }
            }
            assertEquals("access_token_expired", ordinaryFailure.errorCode)
            assertEquals(3, ordinaryCalls.get(), "ordinary refresh must stop after bounded expired responses")
            val ordinaryContext = accounts.loadRefreshContext(ordinaryId)!!
            assertEquals(
                ordinaryRotation.get(),
                cipher.decrypt(ordinaryContext.refreshTokenCiphertext, ordinaryContext.refreshTokenNonce),
            )

            val forced = withTimeout(5_000) { forcedService.refreshNow(forcedId) }
            val forcedFailure = assertIs<OAuthRefreshResult.Failed>(forced)
            assertEquals("access_token_expired", forcedFailure.errorCode)
            assertEquals(3, forcedCalls.get(), "refreshNow must share the expired-token bound")
            val forcedContext = accounts.loadRefreshContext(forcedId)!!
            assertEquals(
                forcedRotation.get(),
                cipher.decrypt(forcedContext.refreshTokenCiphertext, forcedContext.refreshTokenNonce),
            )

            val diagnosticAccount = accounts.find(diagnosticId)!!
            val diagnosticFailure = assertFailsWith<OAuthTokenUnavailableException> {
                withTimeout(5_000) {
                    diagnosticService.accessTokenForDiagnostic(
                        diagnosticAccount,
                        DiagnosticPreparationDeadline.start(5_000),
                    )
                }
            }
            assertEquals("access_token_expired", diagnosticFailure.errorCode)
            assertEquals(3, diagnosticCalls.get(), "diagnostic refresh must share the expired-token bound")
            val diagnosticContext = accounts.loadRefreshContext(diagnosticId)!!
            assertEquals(
                diagnosticRotation.get(),
                cipher.decrypt(diagnosticContext.refreshTokenCiphertext, diagnosticContext.refreshTokenNonce),
            )
        } finally {
            assertTrue(ordinaryService.drainRefreshes())
            assertTrue(forcedService.drainRefreshes())
            assertTrue(diagnosticService.drainRefreshes())
        }
    }

    @Test
    fun tokenThatExpiresDuringSaveIsPersistedThenReplacedByOneFreshExchange() = runBlocking {
        val initialRefresh = UUID.randomUUID().toString()
        val firstRotation = UUID.randomUUID().toString()
        val secondRotation = UUID.randomUUID().toString()
        val validAccess = UUID.randomUUID().toString()
        val firstRequest = AtomicReference<String>()
        val secondRequest = AtomicReference<String>()
        val calls = AtomicInteger()
        val baseTime = System.currentTimeMillis()
        val clock = AtomicLong(baseTime)
        val accountId = createAccount(initialRefresh, accountEmail = email("expired-during-save"))
        val service = serviceWithProvider(
            provider = object : OAuthTokenProvider {
                override val provider = OAuthProvider.CHATGPT

                override suspend fun refresh(refreshToken: String, proxyUrl: String?): OAuthTokenResponse {
                    when (calls.incrementAndGet()) {
                        1 -> {
                            assertEquals(initialRefresh, refreshToken)
                            firstRequest.set(refreshToken)
                            return OAuthTokenResponse(
                                UUID.randomUUID().toString(),
                                firstRotation,
                                expiresInSeconds = 1,
                            )
                        }
                        else -> {
                            assertEquals(firstRotation, refreshToken)
                            val persisted = accounts.loadRefreshContext(accountId)!!
                            assertEquals(
                                firstRotation,
                                cipher.decrypt(persisted.refreshTokenCiphertext, persisted.refreshTokenNonce),
                                "the recovery exchange must wait for the first rotation to commit",
                            )
                            secondRequest.set(refreshToken)
                            return OAuthTokenResponse(validAccess, secondRotation, expiresInSeconds = 3_600)
                        }
                    }
                }
            },
            now = clock::get,
        )

        try {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { lock ->
                lock.autoCommit = false
                val blockerPid = lock.createStatement().use { statement ->
                    statement.executeQuery("select pg_backend_pid()").use { rows ->
                        assertTrue(rows.next())
                        rows.getInt(1)
                    }
                }
                lock.prepareStatement("select id from oauth_accounts where id = ? for update").use { statement ->
                    statement.setString(1, accountId)
                    statement.executeQuery().use { assertTrue(it.next()) }
                }
                val caller = async(Dispatchers.IO) { service.accessToken(accountId) }
                try {
                    awaitBlockedAccountSave(blockerPid)
                    clock.set(baseTime + 2_000)
                    lock.rollback()
                    val token = withTimeout(5_000) { caller.await() }
                    assertEquals(validAccess, assertNotNull(token).accessToken)
                    assertEquals(2, calls.get(), "one expired save must cause only one recovery exchange")
                    assertEquals(initialRefresh, firstRequest.get())
                    assertEquals(firstRotation, secondRequest.get())
                    val context = accounts.loadRefreshContext(accountId)!!
                    assertEquals(secondRotation, cipher.decrypt(context.refreshTokenCiphertext, context.refreshTokenNonce))
                } finally {
                    caller.cancelAndJoin()
                    lock.rollback()
                }
            }
        } finally {
            assertTrue(service.drainRefreshes())
        }
    }

    @Test
    fun diagnosticRefreshKeepsRotationAfterCallerCancellationAndReusesSavedToken() = runBlocking {
        val keyId = createAccount("rt-diagnostic-cancel", accountEmail = email("diagnostic-cancel"))
        val service = service()
        val account = accounts.find(keyId)!!
        val exchangeStarted = CompletableDeferred<Unit>()
        val finishExchange = CompletableDeferred<Unit>()
        beforeReply.set {
            exchangeStarted.complete(Unit)
            runBlocking { withTimeout(10_000) { finishExchange.await() } }
        }

        try {
            val waiter = async {
                service.accessTokenForDiagnostic(account, DiagnosticPreparationDeadline.start(5_000))
            }
            withTimeout(5_000) { exchangeStarted.await() }
            waiter.cancelAndJoin()
            finishExchange.complete(Unit)

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var saved: dev.soven.prismllm.oauth.domain.OAuthAccountRecord?
            do {
                saved = accounts.find(keyId)
                if (saved?.hasAccessToken == true) break
                delay(20)
            } while (System.nanoTime() < deadline)
            assertTrue(saved?.hasAccessToken == true, "service-owned refresh did not persist the response")
            val rotated = accounts.loadRefreshContext(keyId)!!
            assertEquals("rt-2", cipher.decrypt(rotated.refreshTokenCiphertext, rotated.refreshTokenNonce))
            assertEquals(1, tokenRequests.get())
            val cached = service.accessTokenForDiagnostic(saved!!, DiagnosticPreparationDeadline.start(1_000))
            assertEquals("at-1", cached.accessToken)
            assertTrue(cached.matches(saved))
            assertEquals(1, tokenRequests.get())
        } finally {
            finishExchange.complete(Unit)
            assertTrue(service.drainRefreshes())
        }
    }

    @Test
    fun diagnosticCallerTimeoutDoesNotStopRefreshOrTheRotatedCredentialSave() = runBlocking {
        val keyId = createAccount("rt-diagnostic-timeout", accountEmail = email("diagnostic-timeout"))
        val service = service()
        val account = accounts.find(keyId)!!
        val exchangeStarted = CompletableDeferred<Unit>()
        val finishExchange = CompletableDeferred<Unit>()
        beforeReply.set {
            exchangeStarted.complete(Unit)
            runBlocking { withTimeout(10_000) { finishExchange.await() } }
        }

        try {
            val waiter = async {
                runCatching {
                    service.accessTokenForDiagnostic(account, DiagnosticPreparationDeadline.start(1_500))
                }
            }
            withTimeout(5_000) { exchangeStarted.await() }
            val failure = waiter.await().exceptionOrNull()
            assertTrue(DiagnosticPreparationDeadline.isTimeout(assertNotNull(failure)))
            finishExchange.complete(Unit)
            awaitSavedAccessToken(keyId)
            val rotated = accounts.loadRefreshContext(keyId)!!
            assertEquals("rt-2", cipher.decrypt(rotated.refreshTokenCiphertext, rotated.refreshTokenNonce))
            assertEquals(1, tokenRequests.get())
        } finally {
            finishExchange.complete(Unit)
            assertTrue(service.drainRefreshes())
        }
    }

    @Test
    fun diagnosticRefreshSurvivesCallerTimeoutWhilePostgresHoldsTheAccountRow() = runBlocking {
        val keyId = createAccount("rt-diagnostic-row-lock", accountEmail = email("diagnostic-row-lock"))
        val service = service()
        val account = accounts.find(keyId)!!
        val exchangeStarted = CompletableDeferred<Unit>()
        val finishExchange = CompletableDeferred<Unit>()
        beforeReply.set {
            exchangeStarted.complete(Unit)
            runBlocking { withTimeout(10_000) { finishExchange.await() } }
        }

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { lock ->
            lock.autoCommit = false
            try {
                lock.prepareStatement("select id from oauth_accounts where id = ? for update").use { statement ->
                    statement.setString(1, keyId)
                    statement.executeQuery().use { assertTrue(it.next()) }
                }
                val waiter = async {
                    runCatching {
                        service.accessTokenForDiagnostic(account, DiagnosticPreparationDeadline.start(1_500))
                    }
                }
                withTimeout(5_000) { exchangeStarted.await() }
                val failure = waiter.await().exceptionOrNull()
                assertTrue(DiagnosticPreparationDeadline.isTimeout(assertNotNull(failure)))
                finishExchange.complete(Unit)
            } finally {
                finishExchange.complete(Unit)
                lock.rollback()
            }
        }

        try {
            val saved = awaitSavedAccessToken(keyId)
            val context = accounts.loadRefreshContext(keyId)!!
            assertEquals("rt-2", cipher.decrypt(context.refreshTokenCiphertext, context.refreshTokenNonce))
            val reused = service.accessTokenForDiagnostic(saved, DiagnosticPreparationDeadline.start(1_000))
            assertEquals("at-1", reused.accessToken)
            assertEquals(1, tokenRequests.get())
        } finally {
            assertTrue(service.drainRefreshes())
        }
    }

    @Test
    fun ordinaryCallerCancellationStillSavesRotatedRefreshToken() = runBlocking {
        val keyId = createAccount("rt-ordinary-cancel", accountEmail = email("ordinary-cancel"))
        val service = service()
        val exchangeStarted = CompletableDeferred<Unit>()
        val finishExchange = CompletableDeferred<Unit>()
        beforeReply.set {
            exchangeStarted.complete(Unit)
            runBlocking { withTimeout(10_000) { finishExchange.await() } }
        }

        try {
            val caller = async(Dispatchers.Default) { service.accessToken(keyId) }
            withTimeout(5_000) { exchangeStarted.await() }
            caller.cancelAndJoin()
            finishExchange.complete(Unit)

            val saved = awaitSavedAccessToken(keyId)
            val context = accounts.loadRefreshContext(keyId)!!
            assertEquals("rt-2", cipher.decrypt(context.refreshTokenCiphertext, context.refreshTokenNonce))
            assertEquals("at-1", cipher.decrypt(saved.accessTokenCiphertext!!, saved.accessTokenNonce!!))
            assertEquals(1, tokenRequests.get(), "caller cancellation must not repeat the provider exchange")
        } finally {
            finishExchange.complete(Unit)
            assertTrue(service.drainRefreshes())
        }
    }

    @Test
    fun diagnosticRefreshRetriesAfterTheRealPostgresSaveDeadlineAndReusesSavedToken() = runBlocking {
        val keyId = createAccount("rt-save-timeout", accountEmail = email("save-timeout"))
        val service = service()
        val account = accounts.find(keyId)!!
        val exchangeStarted = CompletableDeferred<Unit>()
        val finishExchange = CompletableDeferred<Unit>()
        beforeReply.set {
            exchangeStarted.complete(Unit)
            runBlocking { withTimeout(10_000) { finishExchange.await() } }
        }

        try {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { lock ->
                lock.autoCommit = false
                val blockerPid = lock.createStatement().use { statement ->
                    statement.executeQuery("select pg_backend_pid()").use { rows ->
                        assertTrue(rows.next())
                        rows.getInt(1)
                    }
                }
                try {
                    val waiter = async(Dispatchers.Default) {
                        runCatching {
                            service.accessTokenForDiagnostic(account, DiagnosticPreparationDeadline.start(1_500))
                        }
                    }
                    withTimeout(5_000) { exchangeStarted.await() }
                    lock.prepareStatement("select id from oauth_accounts where id = ? for update").use { statement ->
                        statement.setString(1, keyId)
                        statement.executeQuery().use { assertTrue(it.next()) }
                    }
                    finishExchange.complete(Unit)

                    // Observe the first actual repository save blocked on the held account row.
                    val firstSave = awaitBlockedAccountSave(blockerPid)
                    val diagnosticFailure = waiter.await().exceptionOrNull()
                    assertTrue(
                        DiagnosticPreparationDeadline.isTimeout(assertNotNull(diagnosticFailure)),
                        "the 1.5-second diagnostic waiter must return independently of service persistence",
                    )

                    // The service drains the real first transaction before retrying. Observe that
                    // the first PostgreSQL statement is gone, then confirm a retry is blocked.
                    awaitAccountSaveEnded(firstSave)
                    awaitBlockedAccountSave(blockerPid, after = firstSave.queryStart)
                } finally {
                    lock.rollback()
                }
            }

            val saved = awaitSavedAccessToken(keyId)
            val context = accounts.loadRefreshContext(keyId)!!
            assertEquals("rt-2", cipher.decrypt(context.refreshTokenCiphertext, context.refreshTokenNonce))
            assertEquals("at-1", cipher.decrypt(saved.accessTokenCiphertext!!, saved.accessTokenNonce!!))
            val reused = service.accessTokenForDiagnostic(saved, DiagnosticPreparationDeadline.start(1_000))
            assertEquals("at-1", reused.accessToken)
            assertTrue(reused.matches(saved))
            assertEquals(1, tokenRequests.get(), "a persistence retry must never repeat the provider exchange")
        } finally {
            finishExchange.complete(Unit)
            assertTrue(service.drainRefreshes())
        }
    }

    private data class BlockedAccountSave(val backendPid: Int, val queryStart: java.sql.Timestamp)

    private suspend fun awaitBlockedAccountSave(
        blockerPid: Int,
        after: java.sql.Timestamp? = null,
    ): BlockedAccountSave = withTimeout(16_000) {
        while (true) {
            val blocked = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { observer ->
                observer.prepareStatement(
                    "select pid, query_start from pg_stat_activity " +
                        "where datname = current_database() and query ilike '%oauth_accounts%' " +
                        "and $blockerPid = any(pg_blocking_pids(pid)) and state = 'active' " +
                        "and wait_event_type = 'Lock' " +
                        (if (after == null) "" else "and query_start > ? ") +
                        "order by query_start desc limit 1",
                ).use { statement ->
                    if (after != null) statement.setTimestamp(1, after)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) BlockedAccountSave(rows.getInt("pid"), rows.getTimestamp("query_start")) else null
                    }
                }
            }
            if (blocked != null) return@withTimeout blocked
            // Poll PostgreSQL's lock graph instead of inferring lock state from elapsed time.
            delay(10)
        }
        error("unreachable")
    }

    private suspend fun awaitAccountSaveEnded(save: BlockedAccountSave) = withTimeout(16_000) {
        while (true) {
            val stillActive = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { observer ->
                observer.prepareStatement(
                    "select 1 from pg_stat_activity where pid = ? and query_start = ? " +
                        "and state = 'active' and query ilike '%oauth_accounts%'",
                ).use { statement ->
                    statement.setInt(1, save.backendPid)
                    statement.setTimestamp(2, save.queryStart)
                    statement.executeQuery().use { it.next() }
                }
            }
            if (!stillActive) return@withTimeout
            delay(10)
        }
        error("unreachable")
    }

    private suspend fun awaitSavedAccessToken(accountId: String): OAuthAccountRecord {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var account = accounts.find(accountId)
        while (account?.hasAccessToken != true && System.nanoTime() < deadline) {
            delay(20)
            account = accounts.find(accountId)
        }
        return assertNotNull(account?.takeIf { it.hasAccessToken }, "service-owned refresh did not persist the response")
    }

    @Test
    fun concurrentDiagnosticWaitersShareOneRefreshExchange() = runBlocking {
        val keyId = createAccount("rt-diagnostic-concurrent", accountEmail = email("diagnostic-concurrent"))
        val service = service()
        val account = accounts.find(keyId)!!
        val exchangeStarted = CompletableDeferred<Unit>()
        val finishExchange = CompletableDeferred<Unit>()
        beforeReply.set {
            exchangeStarted.complete(Unit)
            runBlocking { withTimeout(10_000) { finishExchange.await() } }
        }

        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                service.accessTokenForDiagnostic(account, DiagnosticPreparationDeadline.start(5_000))
            }
            withTimeout(5_000) { exchangeStarted.await() }
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                service.accessTokenForDiagnostic(account, DiagnosticPreparationDeadline.start(5_000))
            }
            finishExchange.complete(Unit)

            assertEquals("at-1", first.await().accessToken)
            assertEquals("at-1", second.await().accessToken)
            assertEquals(1, tokenRequests.get())
        } finally {
            finishExchange.complete(Unit)
            assertTrue(service.drainRefreshes())
        }
    }

    @Test
    fun expiredFirstDiagnosticWaiterDoesNotConsumeSecondWaiterBudget() = runBlocking {
        val keyId = createAccount("rt-diagnostic-independent-budget", accountEmail = email("diagnostic-independent-budget"))
        val service = service()
        val account = accounts.find(keyId)!!
        val exchangeStarted = CompletableDeferred<Unit>()
        val finishExchange = CompletableDeferred<Unit>()
        beforeReply.set {
            exchangeStarted.complete(Unit)
            runBlocking { withTimeout(10_000) { finishExchange.await() } }
        }

        try {
            // Deterministically park the shared task in the per-account mutex before either
            // diagnostic waiter joins. Each waiter then retains only its own budget.
            val mutex = accountMutex(service, keyId)
            val owner = Any()
            mutex.lock(owner)
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    service.accessTokenForDiagnostic(account, DiagnosticPreparationDeadline.start(1_500))
                }
            }
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    service.accessTokenForDiagnostic(account, DiagnosticPreparationDeadline.start(10_000))
                }
            }
            try {
                val failure = first.await().exceptionOrNull()
                assertTrue(DiagnosticPreparationDeadline.isTimeout(assertNotNull(failure)))
                mutex.unlock(owner)
                withTimeout(5_000) { exchangeStarted.await() }
                finishExchange.complete(Unit)
                assertEquals("at-1", withTimeout(5_000) { second.await() }.getOrThrow().accessToken)
                assertEquals(1, tokenRequests.get())
            } finally {
                if (mutex.holdsLock(owner)) mutex.unlock(owner)
            }
        } finally {
            finishExchange.complete(Unit)
            assertTrue(service.drainRefreshes())
        }
    }

    @Test
    fun diagnosticWaiterThatTimesOutBeforeExchangeDoesNotStartProviderRequest() = runBlocking {
        val keyId = createAccount("rt-diagnostic-no-waiters", accountEmail = email("diagnostic-no-waiters"))
        val service = service()
        val account = accounts.find(keyId)!!
        val mutex = accountMutex(service, keyId)
        val owner = Any()
        mutex.lock(owner)

        try {
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    service.accessTokenForDiagnostic(account, DiagnosticPreparationDeadline.start(1_500))
                }
            }
            val failure = waiter.await().exceptionOrNull()
            assertTrue(DiagnosticPreparationDeadline.isTimeout(assertNotNull(failure)))
            mutex.unlock(owner)
            awaitRefreshTasksEnded(service)
            assertEquals(0, tokenRequests.get(), "the shared task must recheck active waiters before exchange")
            assertEquals("at-1", service.accessToken(keyId)!!.accessToken)
            assertEquals(1, tokenRequests.get(), "The service must still accept refreshes after the abandoned task exits")
        } finally {
            if (mutex.holdsLock(owner)) mutex.unlock(owner)
            assertTrue(service.drainRefreshes())
        }
    }

    @Test
    fun cancelledForcedWaiterCannotAuthorizeAnOrdinaryExchangeAfterPermitWait() = runBlocking {
        val accountId = createAccount("force-eligibility", accountEmail = email("force-eligibility"))
        accounts.recordFailure(accountId, "invalid_grant", terminal = true)
        val providerCalls = AtomicInteger()
        val service = serviceWithProvider(object : OAuthTokenProvider {
            override val provider = OAuthProvider.CHATGPT
            override suspend fun refresh(refreshToken: String, proxyUrl: String?): OAuthTokenResponse {
                providerCalls.incrementAndGet()
                return OAuthTokenResponse("must-not-be-used")
            }
        })
        val field = OAuthTokenService::class.java.getDeclaredField("refreshPermits").apply { isAccessible = true }
        val permits = field.get(service) as kotlinx.coroutines.sync.Semaphore
        repeat(4) { permits.acquire() }
        val ordinarySnapshot = accounts.find(accountId)!!
        val forced = async(start = CoroutineStart.UNDISPATCHED) { service.refreshNow(accountId) }
        val ordinary = async(start = CoroutineStart.UNDISPATCHED) { runCatching { service.accessToken(accountId) } }
        try {
            awaitExchangePermitWait(service, accountId)
            forced.cancelAndJoin()
            repeat(4) { permits.release() }
            val failure = withTimeout(5_000) { ordinary.await() }.exceptionOrNull()
            assertEquals(OAuthUnavailableReason.REAUTH_REQUIRED, assertIs<OAuthTokenUnavailableException>(failure).reason)
            assertEquals(0, providerCalls.get())
            assertEquals(ordinarySnapshot.refreshTokenCiphertext, accounts.find(accountId)!!.refreshTokenCiphertext)
        } finally {
            while (permits.availablePermits < 4) permits.release()
            forced.cancelAndJoin()
            assertTrue(service.drainRefreshes())
        }
    }

    @Test
    fun queuedDiagnosticRechecksCredentialAndProxyBeforeStartingAnExchange() = runBlocking {
        val accountId = createAccount("queued-snapshot", accountEmail = email("queued-snapshot"))
        val providerCalls = AtomicInteger()
        val service = serviceWithProvider(object : OAuthTokenProvider {
            override val provider = OAuthProvider.CHATGPT
            override suspend fun refresh(refreshToken: String, proxyUrl: String?): OAuthTokenResponse {
                providerCalls.incrementAndGet()
                return OAuthTokenResponse("must-not-be-used")
            }
        })
        val field = OAuthTokenService::class.java.getDeclaredField("refreshPermits").apply { isAccessible = true }
        val permits = field.get(service) as kotlinx.coroutines.sync.Semaphore
        repeat(4) { permits.acquire() }
        val original = accounts.find(accountId)!!
        val caller = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { service.accessTokenForDiagnostic(original, DiagnosticPreparationDeadline.start(10_000)) }
        }
        try {
            awaitExchangePermitWait(service, accountId)
            accounts.updateSettings(accountId, proxyUrl = "http://127.0.0.1:8181")
            repeat(4) { permits.release() }
            val failure = withTimeout(5_000) { caller.await() }.exceptionOrNull()
            assertEquals("credential_replaced", assertIs<OAuthTokenUnavailableException>(failure).errorCode)
            assertEquals(0, providerCalls.get(), "the original egress must not be used after permit waiting")
            assertEquals("http://127.0.0.1:8181", accounts.find(accountId)!!.proxyUrl)
        } finally {
            while (permits.availablePermits < 4) permits.release()
            caller.cancelAndJoin()
            assertTrue(service.drainRefreshes())
        }
    }

    @Test
    fun accountsWithTheSameFormerStripeDoNotBlockEachOthersPersistence() = runBlocking {
        val calls = AtomicInteger()
        val service = serviceWithProvider(object : OAuthTokenProvider {
            override val provider = OAuthProvider.CHATGPT
            override suspend fun refresh(refreshToken: String, proxyUrl: String?): OAuthTokenResponse {
                calls.incrementAndGet()
                return OAuthTokenResponse("access-$refreshToken", "rotated-$refreshToken")
            }
        })
        val firstId = createAccount("same-stripe-first", accountEmail = email("same-stripe-first"))
        val oldStripe = (firstId.hashCode() and Int.MAX_VALUE) % 64
        var secondId: String
        do { secondId = createAccount("same-stripe-second", accountEmail = email("same-stripe-second")) }
        while ((secondId.hashCode() and Int.MAX_VALUE) % 64 != oldStripe)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { lock ->
            lock.autoCommit = false
            try {
                lock.prepareStatement("select id from oauth_accounts where id = ? for update").use { statement ->
                    statement.setString(1, firstId)
                    statement.executeQuery().use { assertTrue(it.next()) }
                }
                val pid = lock.createStatement().use { it.executeQuery("select pg_backend_pid()").use { row -> row.next(); row.getInt(1) } }
                val first = async(Dispatchers.IO) { service.accessToken(firstId) }
                awaitBlockedAccountSave(pid)
                val second = withTimeout(5_000) { service.accessToken(secondId) }
                assertEquals("access-same-stripe-second", second!!.accessToken)
                assertEquals(2, calls.get())
                lock.rollback()
                assertNotNull(withTimeout(5_000) { first.await() })
            } finally {
                lock.rollback()
            }
        }
        assertTrue(service.drainRefreshes())
    }

    @Test
    fun diagnosticAcceptsOnlyServiceRecordedNormalRotationNotAdminAccessOrProxyChanges() = runBlocking {
        val keyId = createAccount("rt-normal-rotation", accountEmail = email("normal-rotation"))
        val clock = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val service = service(now = clock::get)
        val original = accounts.find(keyId)!!
        val exchangeStarted = CompletableDeferred<Unit>()
        val finishExchange = CompletableDeferred<Unit>()
        beforeReply.set {
            exchangeStarted.complete(Unit)
            runBlocking { withTimeout(10_000) { finishExchange.await() } }
        }

        try {
            val firstWaiter = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    service.accessTokenForDiagnostic(original, DiagnosticPreparationDeadline.start(5_000))
                }
            }
            withTimeout(5_000) { exchangeStarted.await() }
            finishExchange.complete(Unit)
            val firstAccess = firstWaiter.await().getOrThrow()
            val rotated = awaitSavedAccessToken(keyId)
            assertEquals("rt-2", cipher.decrypt(accounts.loadRefreshContext(keyId)!!.refreshTokenCiphertext,
                accounts.loadRefreshContext(keyId)!!.refreshTokenNonce))
            assertEquals("at-1", firstAccess.accessToken)

            val reused = service.accessTokenForDiagnostic(original, DiagnosticPreparationDeadline.start(1_000))
            assertEquals("at-1", reused.accessToken)
            assertTrue(reused.matches(rotated), "a saved in-process normal rotation may satisfy the prior snapshot")
            assertEquals(1, tokenRequests.get())

            accounts.updateCredentials(
                keyId,
                OAuthAccountCredentialUpdate(
                    provider = OAuthProvider.CHATGPT,
                    accountEmail = rotated.accountEmail,
                    accessToken = cipher.encrypt("at-admin"),
                    accessTokenExpiresAt = clock.get() + 3_600_000,
                    proxyUrl = rotated.proxyUrl,
                ),
            )
            val adminAccess = accounts.find(keyId)!!
            val rejectedAccess = assertFailsWith<OAuthTokenUnavailableException> {
                service.accessTokenForDiagnostic(original, DiagnosticPreparationDeadline.start(1_000))
            }
            assertEquals("credential_replaced", rejectedAccess.errorCode)
            assertEquals(1, tokenRequests.get(), "admin-provided access token must not be treated as normal rotation")
            assertEquals("at-admin", cipher.decrypt(adminAccess.accessTokenCiphertext!!, adminAccess.accessTokenNonce!!))

            // Expire the cached access token without changing the database snapshot, so this
            // path must re-read and reject a proxy-only edit before starting an exchange.
            clock.addAndGet(3_600_001)
            accounts.updateSettings(keyId, proxyUrl = "http://127.0.0.1:8181")
            val proxyChanged = accounts.find(keyId)!!
            assertEquals(adminAccess.authorizedAt, proxyChanged.authorizedAt)
            assertEquals(adminAccess.refreshTokenCiphertext, proxyChanged.refreshTokenCiphertext)
            assertEquals(adminAccess.accessTokenCiphertext, proxyChanged.accessTokenCiphertext)
            val rejectedProxy = assertFailsWith<OAuthTokenUnavailableException> {
                service.accessTokenForDiagnostic(adminAccess, DiagnosticPreparationDeadline.start(1_000))
            }
            assertEquals("credential_replaced", rejectedProxy.errorCode)
            assertEquals(1, tokenRequests.get())
        } finally {
            finishExchange.complete(Unit)
            assertTrue(service.drainRefreshes())
        }
    }

    @Test
    fun diagnosticRefreshCasDoesNotOverwriteAdminReplacement() = runBlocking {
        val keyId = createAccount("rt-diagnostic-old", accountEmail = email("diagnostic-replace"))
        val service = service()
        val account = accounts.find(keyId)!!
        val exchangeStarted = CompletableDeferred<Unit>()
        val finishExchange = CompletableDeferred<Unit>()
        beforeReply.set {
            exchangeStarted.complete(Unit)
            runBlocking { withTimeout(10_000) { finishExchange.await() } }
        }

        try {
            val waiter = async {
                runCatching {
                    service.accessTokenForDiagnostic(account, DiagnosticPreparationDeadline.start(5_000))
                }
            }
            withTimeout(5_000) { exchangeStarted.await() }
            accounts.updateCredentials(
                keyId,
                OAuthAccountCredentialUpdate(
                    provider = OAuthProvider.CHATGPT,
                    accountEmail = email("diagnostic-replace-new"),
                    refreshToken = cipher.encrypt("rt-diagnostic-new"),
                ),
            )
            finishExchange.complete(Unit)

            val failure = waiter.await().exceptionOrNull()
            assertEquals("credential_replaced", assertIs<OAuthTokenUnavailableException>(failure).errorCode)
            val context = accounts.loadRefreshContext(keyId)!!
            assertEquals("rt-diagnostic-new", cipher.decrypt(context.refreshTokenCiphertext, context.refreshTokenNonce))
            assertNull(context.account.accessTokenCiphertext)
            assertEquals(1, tokenRequests.get())
        } finally {
            finishExchange.complete(Unit)
            assertTrue(service.drainRefreshes())
        }
    }

    @Test
    fun diagnosticBudgetPauseResumeAndNestedScopesShareTheSameActiveDeadline() = runBlocking {
        val clock = java.util.concurrent.atomic.AtomicLong()
        fun advance(milliseconds: Long) { clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(milliseconds)) }
        val budget = DiagnosticPreparationDeadline.start(300, clock::get)
        assertEquals(true, DiagnosticPreparationDeadline.within(budget) {
            advance(100)
            true
        })
        budget.pause()
        advance(5_000)
        budget.resume()
        assertEquals(true, DiagnosticPreparationDeadline.within(budget) {
            advance(100)
            true
        })
        assertNull(DiagnosticPreparationDeadline.within(budget) {
            advance(101)
            true
        }, "Entering a new scope cannot replenish the budget after pause/resume")

        val nested = DiagnosticPreparationDeadline.start(100, clock::get)
        assertNull(DiagnosticPreparationDeadline.within(nested) {
            advance(60)
            DiagnosticPreparationDeadline.within(nested) {
                advance(41)
                true
            } ?: false
        })
    }

    @Test
    fun terminalRefreshFailureMarksAccountForReauthorization() = runBlocking {
        reply = { 400 to """{"error":"invalid_grant","error_description":"refresh token revoked"}""" }
        val keyId = createAccount("rt-terminal", accountEmail = email("terminal"))
        val service = service()

        val error = assertFailsWith<OAuthTokenUnavailableException> { service.accessToken(keyId) }
        assertEquals(OAuthUnavailableReason.REAUTH_REQUIRED, error.reason)
        assertEquals("invalid_grant", error.errorCode)

        val account = accounts.find(keyId)!!
        assertEquals(OAuthAuthState.REAUTH_REQUIRED, account.authState)
        assertEquals(1, account.refreshFailureCount)
        assertEquals("invalid_grant", account.lastRefreshError)

        // 终态账号不再自动请求上游，也绝不回退到 refresh token。
        val hits = tokenRequests.get()
        assertFailsWith<OAuthTokenUnavailableException> { service.accessToken(keyId) }
        assertEquals(hits, tokenRequests.get())
        val accountForResolve = accounts.find(keyId)!!
        assertFailsWith<OAuthTokenUnavailableException> { service.resolveCredential(accountForResolve) }

        // 管理面手动刷新允许再试一次。
        val result = service.refreshNow(keyId)
        assertIs<OAuthRefreshResult.Failed>(result)
        assertEquals(hits + 1, tokenRequests.get())
    }

    @Test
    fun `a failure does not overwrite credentials imported during the refresh`() = runBlocking {
        reply = { 400 to """{"error":"invalid_grant","error_description":"refresh token revoked"}""" }
        val keyId = createAccount("rt-old", accountEmail = email("replace"))
        // 模拟管理员在刷新请求进行期间重新导入凭据。
        beforeReply.set {
            runBlocking {
                accounts.updateCredentials(
                    keyId,
                    OAuthAccountCredentialUpdate(
                        provider = OAuthProvider.CHATGPT,
                        accountEmail = email("replace-new"),
                        refreshToken = cipher.encrypt("rt-new"),
                    ),
                )
            }
        }
        val service = service()

        val error = assertFailsWith<OAuthTokenUnavailableException> { service.accessToken(keyId) }
        assertEquals(OAuthUnavailableReason.REFRESH_FAILED, error.reason)
        assertEquals("credential_replaced", error.errorCode)

        // 旧 refresh token 的终态错误不会把刚导入的新凭据标记为需要重新授权。
        val account = accounts.find(keyId)!!
        assertEquals(OAuthAuthState.ACTIVE, account.authState)
        assertEquals(0, account.refreshFailureCount)
        assertNull(account.lastRefreshError)
        val context = accounts.loadRefreshContext(keyId)!!
        assertEquals("rt-new", cipher.decrypt(context.refreshTokenCiphertext, context.refreshTokenNonce))
    }

    @Test
    fun transientRefreshFailureKeepsAccountActive() = runBlocking {
        reply = { 503 to "{}" }
        val keyId = createAccount("rt-transient", accountEmail = email("transient"))
        val service = service()

        val error = assertFailsWith<OAuthTokenUnavailableException> { service.accessToken(keyId) }
        assertEquals(OAuthUnavailableReason.REFRESH_FAILED, error.reason)
        assertEquals("http_503", error.errorCode)

        val account = accounts.find(keyId)!!
        assertEquals(OAuthAuthState.ACTIVE, account.authState)
        assertEquals(1, account.refreshFailureCount)
        assertEquals("http_503", account.lastRefreshError)
    }

    @Test
    fun staticKeysKeepUsingTheirOwnCredential() = runBlocking {
        val channel = createChannel("static-secret")
        val key = channel.keys.single()
        val service = service()

        assertNull(service.accessToken(key.id))
        assertEquals("static-secret", service.resolveCredential(key))
        assertEquals(0, tokenRequests.get())
    }

    @Test
    fun `built-in client credentials register both providers`() {
        val registry = OAuthTokenProviderRegistry(buildOAuthTokenProviders(OAuthConfig()))
        assertEquals(setOf(OAuthProvider.CHATGPT, OAuthProvider.ANTIGRAVITY), registry.available())
    }

    @Test
    fun unregisteredProviderIsReportedWithoutCallingUpstream() = runBlocking {
        // 该服务只装配了 ChatGPT provider，未注册的提供方不应尝试刷新。
        val keyId = createAccount("rt-1", provider = OAuthProvider.ANTIGRAVITY, accountEmail = email("antigravity"))
        val service = service()

        val error = assertFailsWith<OAuthTokenUnavailableException> { service.accessToken(keyId) }
        assertEquals(OAuthUnavailableReason.PROVIDER_NOT_CONFIGURED, error.reason)
        assertEquals(0, tokenRequests.get())
    }

    @Test
    fun refreshScanSkipsAccountsWaitingForReauthorization() = runBlocking {
        val dueId = createAccount("rt-due", accountEmail = email("due"))
        val terminalId = createAccount("rt-terminal-scan", accountEmail = email("reauth"))
        accounts.recordFailure(terminalId, "invalid_grant", terminal = true)

        val due = accounts.listDueForRefresh(System.currentTimeMillis() + 900_000, limit = 1_000)
        assertTrue(due.any { it.id == dueId })
        assertFalse(due.any { it.id == terminalId })
    }

    @Test
    fun rejectsUnsupportedAccountProxy() {
        assertFailsWith<OAuthRefreshException> { proxyAddress("socks5://127.0.0.1:1080") }
        assertFailsWith<OAuthRefreshException> { proxyAddress("http://user:pass@127.0.0.1:8080") }
        assertFailsWith<OAuthRefreshException> { proxyAddress("http://127.0.0.1:8080/path") }
        assertNull(proxyAddress(null))
        assertEquals(8080, proxyAddress("http://127.0.0.1:8080")!!.port)
        assertEquals(8080, proxyAddress("http://LOCALHOST:8080")!!.port)
    }

    @Test
    fun `account proxy hosts outside loopback require an explicit allowlist`() {
        val denied = assertFailsWith<OAuthRefreshException> { proxyAddress("http://proxy.example.com:8080") }
        assertEquals("proxy_not_allowed", denied.errorCode)

        // 白名单条目统一小写，URI 主机大小写不敏感。
        assertEquals(
            8080,
            proxyAddress("http://Proxy.Example.com:8080", setOf("proxy.example.com"))!!.port,
        )
        assertTrue(isProxyHostAllowed("[::1]", emptySet()))
        assertFalse(isProxyHostAllowed("10.0.0.12", emptySet()))
        assertTrue(isProxyHostAllowed("10.0.0.12", setOf("10.0.0.12")))
    }

    @Test
    fun `refresh near the clock limit saturates the access token expiry`() = runBlocking {
        val keyId = createAccount("rt-overflow", accountEmail = email("overflow"))
        val service = service(now = { Long.MAX_VALUE - 10 })

        service.accessToken(keyId, forceRefresh = true)

        val account = assertNotNull(accounts.find(keyId))
        assertEquals(Long.MAX_VALUE, account.accessTokenExpiresAt)
    }

    private suspend fun awaitRefreshTasksEnded(service: OAuthTokenService) = withTimeout(5_000) {
        val tasks = OAuthTokenService::class.java.getDeclaredField("refreshTasks").apply { isAccessible = true }
        val monitor = OAuthTokenService::class.java.getDeclaredField("refreshLifecycleLock").apply { isAccessible = true }.get(service)
        while (synchronized(monitor) { (tasks.get(service) as Map<*, *>).isNotEmpty() }) delay(1)
    }

    private suspend fun awaitExchangePermitWait(service: OAuthTokenService, accountId: String) = withTimeout(5_000) {
        val tasksField = OAuthTokenService::class.java.getDeclaredField("refreshTasks").apply { isAccessible = true }
        val monitorField = OAuthTokenService::class.java.getDeclaredField("refreshLifecycleLock").apply { isAccessible = true }
        while (true) {
            val waiting = synchronized(monitorField.get(service)) {
                val task = (tasksField.get(service) as Map<*, *>)[accountId]
                task != null && task.javaClass.getDeclaredField("waitingForExchangePermit").apply { isAccessible = true }.getBoolean(task)
            }
            if (waiting) return@withTimeout
            delay(1)
        }
    }

    private fun accountMutex(service: OAuthTokenService, accountId: String): kotlinx.coroutines.sync.Mutex {
        val method = OAuthTokenService::class.java.getDeclaredMethod("lockFor", String::class.java).apply { isAccessible = true }
        return method.invoke(service, accountId) as kotlinx.coroutines.sync.Mutex
    }

    private fun serviceWithProvider(
        provider: OAuthTokenProvider,
        requestTimeoutMs: Long = 30_000,
        now: () -> Long = System::currentTimeMillis,
    ): OAuthTokenService = OAuthTokenService(
        repository = accounts,
        cipher = cipher,
        providers = OAuthTokenProviderRegistry(listOf(provider)),
        config = OAuthConfig(refreshAheadMs = 900_000, requestTimeoutMs = requestTimeoutMs),
        now = now,
    )

    private fun service(now: () -> Long = System::currentTimeMillis): OAuthTokenService {
        val provider = ChatGptOAuthProvider(
            OAuthProviderConfig(
                clientId = "test-client",
                tokenEndpoint = "http://127.0.0.1:${server.address.port}/token",
            ),
            timeoutMs = 5_000,
        )
        return OAuthTokenService(
            repository = accounts,
            cipher = cipher,
            providers = OAuthTokenProviderRegistry(listOf(provider)),
            config = OAuthConfig(refreshAheadMs = 900_000),
            now = now,
        )
    }

    private fun createChannel(credential: String): ChannelRecord = runBlocking {
        channels.create(
            name = "oauth-" + UUID.randomUUID(),
            baseUrl = "https://chatgpt.example/v1",
            keys = listOf(NewChannelKey("account", cipher.encrypt(credential))),
            authType = "bearer",
            enabled = true,
        )
    }

    private fun createAccount(
        credential: String,
        provider: OAuthProvider = OAuthProvider.CHATGPT,
        accountEmail: String? = null,
        accountPlan: String? = null,
    ): String = runBlocking {
        val channel = createChannel("unused-static-key-${UUID.randomUUID()}")
        accounts.create(
            NewOAuthAccount(
                name = "account-${UUID.randomUUID()}",
                provider = provider,
                accountEmail = accountEmail,
                accountPlan = accountPlan,
                refreshToken = cipher.encrypt(credential),
                channelIds = listOf(channel.id),
            ),
        ).id
    }

    private fun email(prefix: String): String = "$prefix-${UUID.randomUUID()}@example.com"

    private companion object {
        const val SUCCESS =
            """{"access_token":"at-1","refresh_token":"rt-2","expires_in":3600,"token_type":"Bearer"}"""
    }
}
