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
import dev.soven.prismllm.oauth.infra.OAuthTokenProviderRegistry
import dev.soven.prismllm.oauth.infra.isProxyHostAllowed
import dev.soven.prismllm.oauth.infra.proxyAddress
import dev.soven.prismllm.oauth.persistence.NewOAuthAccount
import dev.soven.prismllm.oauth.persistence.OAuthAccountCredentialUpdate
import dev.soven.prismllm.oauth.persistence.OAuthAccountRepository
import dev.soven.prismllm.repo.OAuthProviderChannelLookup
import dev.soven.prismllm.shared.crypto.CredentialCipher
import dev.soven.prismllm.shared.domain.OAuthProvider
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
            exchange.responseBody.use { it.write(bytes) }
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
        reply = { 200 to SUCCESS }
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

                    // The next blocked FOR UPDATE is the retry. Its query_start proves PostgreSQL
                    // canceled the first save at its 10-second statement deadline; keep the lock
                    // until the service has started that retry, then release it in finally.
                    val retrySave = awaitBlockedAccountSave(blockerPid, after = firstSave.queryStart)
                    val retryDelayMs = retrySave.queryStart.time - firstSave.queryStart.time
                    assertTrue(retryDelayMs >= 10_000, "expected retry after 10-second save timeout, got ${retryDelayMs}ms")
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

    private data class BlockedAccountSave(val queryStart: java.sql.Timestamp)

    private suspend fun awaitBlockedAccountSave(
        blockerPid: Int,
        after: java.sql.Timestamp? = null,
    ): BlockedAccountSave = withTimeout(16_000) {
        while (true) {
            val blocked = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { observer ->
                observer.prepareStatement(
                    "select query_start from pg_stat_activity " +
                        "where datname = current_database() and query ilike '%oauth_accounts%' " +
                        "and $blockerPid = any(pg_blocking_pids(pid)) and state = 'active' " +
                        "and wait_event_type = 'Lock' " +
                        (if (after == null) "" else "and query_start > ? ") +
                        "order by query_start desc limit 1",
                ).use { statement ->
                    if (after != null) statement.setTimestamp(1, after)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) BlockedAccountSave(rows.getTimestamp(1)) else null
                    }
                }
            }
            if (blocked != null) return@withTimeout blocked
            // Poll PostgreSQL's lock graph instead of inferring lock state from elapsed time.
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
            withTimeout(5_000) { assertTrue(service.drainRefreshes()) }
            assertEquals(0, tokenRequests.get(), "the shared task must recheck active waiters before exchange")
        } finally {
            if (mutex.holdsLock(owner)) mutex.unlock(owner)
            assertTrue(service.drainRefreshes())
        }
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

        val due = accounts.listDueForRefresh(System.currentTimeMillis() + 900_000)
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

    private fun accountMutex(service: OAuthTokenService, accountId: String): kotlinx.coroutines.sync.Mutex {
        val field = OAuthTokenService::class.java.getDeclaredField("locks").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val locks = field.get(service) as Array<kotlinx.coroutines.sync.Mutex>
        return locks[(accountId.hashCode() and Int.MAX_VALUE) % locks.size]
    }

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
