package dev.soven.prismllm.channel.infra.quota

import dev.soven.prismllm.channel.domain.ChannelQuotaCredentialSource

import dev.soven.prismllm.channel.domain.ChannelKeyRecord
import dev.soven.prismllm.channel.domain.ChannelQuotaSettings
import dev.soven.prismllm.channel.domain.ChannelRecord
import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.shared.domain.KeyCredentialResolver
import dev.soven.prismllm.channel.domain.key.ChannelKeyState
import dev.soven.prismllm.shared.crypto.CredentialCipher
import dev.soven.prismllm.spi.quota.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ChannelQuotaServiceTest {
    private val cipher = CredentialCipher(ByteArray(32) { 7 })
    private val credentials = KeyCredentialResolver { credential ->
        val key = credential as ChannelKeyRecord
        cipher.decrypt(key.credentialCiphertext, key.credentialNonce)
    }

    @Test
    fun queriesSharedBalanceOnceAndExpiresOrInvalidatesCache() = runBlocking {
        var now = 100_000L
        val calls = AtomicInteger()
        val channel = channel(keys = listOf(key("a"), key("b"), key("c")))
        withService(provider { calls.incrementAndGet(); available("100.00") }, now = { now }) { service ->
            val first = service.query(channel)
            assertEquals("100", first.remaining)
            assertEquals(3, first.sharedKeyCount)
            assertEquals("CHANNEL", first.scope)
            assertEquals(channel.keys.first().id, first.queryKey!!.id)
            assertEquals(listOf(first.queryKey), first.attemptedKeys)
            assertFalse(first.cached)
            now += 59_999
            val cached = service.query(channel)
            assertTrue(cached.cached)
            assertEquals(first.queryKey, cached.queryKey)
            assertEquals(first.attemptedKeys, cached.attemptedKeys)
            assertEquals(1, calls.get())
            now++
            assertFalse(service.query(channel).cached)
            assertEquals(2, calls.get())
            assertFalse(service.query(channel.copy(baseUrl = "https://other.example/v1")).cached)
            assertEquals(3, calls.get())
            val settings = channel.quota.copy(config = kotlinx.serialization.json.buildJsonObject { put("a", kotlinx.serialization.json.JsonPrimitive(1)) })
            assertFalse(service.query(channel.copy(quota = settings)).cached)
            assertEquals(4, calls.get())
        }
    }

    @Test
    fun concurrentRequestsForSameChannelShareOnePluginCall() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val channel = channel()
        withService(provider {
            calls.incrementAndGet(); started.countDown()
            check(release.await(3, TimeUnit.SECONDS)); available("42")
        }) { service ->
            val first = async(Dispatchers.Default) { service.query(channel) }
            try {
                assertTrue(withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) })
                val followers = (1..8).map { async { service.query(channel) } }
                release.countDown()
                assertEquals("42", first.await().remaining)
                assertTrue(followers.awaitAll().all { it.cached && it.remaining == "42" })
                assertEquals(1, calls.get())
            } finally { release.countDown() }
        }
    }

    @Test
    fun choosesPriorityKeyAndOnlyRetriesAuthenticationOnce() = runBlocking {
        val seen = mutableListOf<String>()
        val keys = listOf(key("late", 30), key("disabled", 1).copy(state = ChannelKeyState.MANUAL_DISABLED),
            key("first", 10).copy(name = "主密钥"), key("second", 20).copy(name = "备用密钥"))
        withService(provider { request ->
            seen += request.credential
            if (request.credential == "first") ChannelQuotaResult(ChannelQuotaStatus.AUTHENTICATION_FAILED)
            else available("0")
        }) { service ->
            val result = service.query(channel(keys = keys))
            assertEquals("0", result.remaining)
            assertEquals(keys.first { it.name == "备用密钥" }.id, result.queryKey!!.id)
            assertEquals(listOf("主密钥", "备用密钥"), result.attemptedKeys.map { it.name })
            assertEquals(listOf("first", "second"), seen)
        }
        seen.clear()
        withService(provider { seen += it.credential; ChannelQuotaResult(ChannelQuotaStatus.AUTHENTICATION_FAILED) }) { service ->
            val result = service.query(channel(keys = keys))
            assertEquals("AUTHENTICATION_FAILED", result.status)
            assertEquals("备用密钥", result.queryKey!!.name)
            assertEquals(listOf("主密钥", "备用密钥"), result.attemptedKeys.map { it.name })
            assertEquals(listOf("first", "second"), seen)
        }
    }

    @Test
    fun fallbackKeySucceedsWithinTotalTimeoutBudget() = runBlocking {
        val seen = mutableListOf<String>()
        val keys = listOf(
            key("primary", 10).copy(name = "主密钥"),
            key("secondary", 20).copy(name = "备用密钥"),
        )
        withService(provider { request ->
            seen += request.credential
            if (request.credential == "primary") {
                Thread.sleep(100)
                ChannelQuotaResult(ChannelQuotaStatus.AUTHENTICATION_FAILED)
            } else {
                Thread.sleep(50)
                available("88.88")
            }
        }, timeoutMs = 400) { service ->
            val result = service.query(channel(keys = keys))
            assertEquals(ChannelQuotaStatus.AVAILABLE.name, result.status)
            assertEquals("88.88", result.remaining)
            assertEquals("备用密钥", result.queryKey?.name)
            assertEquals(listOf("主密钥", "备用密钥"), result.attemptedKeys.map { it.name })
            assertEquals(listOf("primary", "secondary"), seen)
        }
    }

    @Test
    fun doesNotRetryRateLimitsAndDoesNotChangeKeyState() = runBlocking {
        val calls = AtomicInteger()
        val original = channel(keys = listOf(key("a"), key("b")))
        withService(provider { calls.incrementAndGet(); ChannelQuotaResult(ChannelQuotaStatus.RATE_LIMITED) }) { service ->
            val result = service.query(original)
            assertEquals("RATE_LIMITED", result.status)
            assertNull(result.remaining)
            assertEquals(1, calls.get())
            assertEquals(original.keys.first().id, result.queryKey!!.id)
            assertEquals(1, result.attemptedKeys.size)
            assertTrue(original.keys.all { it.state == ChannelKeyState.ACTIVE })
        }
    }

    @Test
    fun disabledMissingAndNoUsableKeyDoNotCallProvider() = runBlocking {
        withService(provider { error("must not query") }) { service ->
            assertEquals("DISABLED", service.query(channel().copy(quota = ChannelQuotaSettings())).status)
            assertEquals("UNSUPPORTED", service.query(channel().copy(quota = ChannelQuotaSettings("missing"))).status)
            val noKeys = service.query(channel(keys = emptyList()))
            assertEquals("ERROR", noKeys.status)
            assertNull(noKeys.queryKey)
            assertTrue(noKeys.attemptedKeys.isEmpty())
            assertEquals("ERROR", service.query(channel(keys = listOf(key("a").copy(state = ChannelKeyState.MANUAL_DISABLED)))).status)
        }
    }

    @Test
    fun redactsCredentialsFromEveryReturnedTextAndFromExceptions() = runBlocking {
        withService(provider { request ->
            assertFalse(request.toString().contains("top-secret"))
            ChannelQuotaResult(ChannelQuotaStatus.AVAILABLE, BigDecimal("-1.25"),
                unit = "top-secret", planName = "Plan top-secret\n", message = "Key top-secret")
        }) { service ->
            val result = service.query(channel(keys = listOf(key("top-secret"))))
            assertEquals("-1.25", result.remaining)
            assertEquals("[REDACTED]", result.unit)
            assertEquals("Plan [REDACTED]", result.planName)
            assertEquals("Key [REDACTED]", result.message)
            assertEquals("[REDACTED]", result.queryKey!!.name)
            assertFalse(result.toString().contains("top-secret"))
        }
        withService(provider { throw IllegalStateException("top-secret from upstream") }) { service ->
            val result = service.query(channel())
            assertEquals("ERROR", result.status)
            assertFalse(result.toString().contains("top-secret"))
            assertNull(result.remaining)
            assertNotNull(result.queryKey)
            assertEquals(1, result.attemptedKeys.size)
        }
    }

    @Test
    fun timeoutInterruptsPluginAndReturnsNoAmount() = runBlocking {
        val interrupted = CountDownLatch(1)
        val calls = AtomicInteger()
        withService(provider {
            calls.incrementAndGet()
            try { Thread.sleep(10_000); available("10") }
            catch (cause: InterruptedException) { interrupted.countDown(); throw cause }
        }, timeoutMs = 150) { service ->
            val result = service.query(channel())
            assertEquals("ERROR", result.status)
            assertTrue(result.message!!.contains("超时"))
            assertNull(result.remaining)
            assertNotNull(result.queryKey)
            assertEquals(1, result.attemptedKeys.size)
            assertTrue(withContext(Dispatchers.IO) { interrupted.await(3, TimeUnit.SECONDS) })
            val retried = service.query(channel())
            assertFalse(retried.cached)
            assertEquals(2, calls.get())
        }
    }

    @Test
    fun lockWaitTimeIsBoundedByTotalTimeout() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val channel = channel()
        withService(provider {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            available("100")
        }, timeoutMs = 1_000) { service ->
            val begin = System.nanoTime()
            val first = async(Dispatchers.Default) { service.query(channel) }
            try {
                assertTrue(withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) })
                val second = service.query(channel)
                val elapsedMs = (System.nanoTime() - begin) / 1_000_000
                assertEquals("ERROR", second.status)
                assertTrue(second.message!!.contains("超时"))
                assertTrue(elapsedMs < 1_600, "lock-wait scenario took ${elapsedMs}ms")
            } finally {
                release.countDown()
                first.await()
            }
        }
    }

    @Test
    fun boundsConcurrentPluginExecutionsWithoutQueueingMoreWork() = runBlocking {
        val started = CountDownLatch(4)
        val release = CountDownLatch(1)
        withService(provider {
            started.countDown(); check(release.await(5, TimeUnit.SECONDS)); available("1")
        }) { service ->
            // These IDs occupy distinct lock stripes.
            val queries = listOf("a", "b", "c", "d").map { id -> async(Dispatchers.Default) { service.query(channel().copy(id = id)) } }
            try {
                assertTrue(withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) })
                val rejected = service.query(channel().copy(id = "e"))
                assertEquals("ERROR", rejected.status)
                assertTrue(rejected.message!!.contains("繁忙"))
                assertNull(rejected.queryKey)
                assertTrue(rejected.attemptedKeys.isEmpty())
                assertFalse(service.query(channel().copy(id = "e")).cached)
            } finally { release.countDown() }
            assertTrue(queries.awaitAll().all { it.status == "AVAILABLE" })
        }
    }

    @Test
    fun rejectsInvalidResultsInsteadOfShowingMisleadingBalances() = runBlocking {
        assertFailsWith<IllegalArgumentException> { ChannelQuotaResult(ChannelQuotaStatus.ERROR, BigDecimal.ZERO) }
        assertFailsWith<IllegalArgumentException> { ChannelQuotaResult(ChannelQuotaStatus.AVAILABLE) }
        withService(provider { available("1E100") }) { service ->
            val result = service.query(channel())
            assertEquals("ERROR", result.status)
            assertNull(result.remaining)
        }
    }

    @Test
    fun mixedAccountsReportTheActualSourceWithoutAccumulatingBalances() = runBlocking {
        val welfare = key("welfare-secret", 1).copy(name = "福利密钥")
        val owned = key("owned-secret", 2).copy(name = "自有密钥")
        val original = channel(keys = listOf(owned, welfare))
        val seen = mutableListOf<String>()
        withService(provider { request ->
            seen += request.credential
            available(if (request.credential == "welfare-secret") "99064.6903981" else "100")
        }) { service ->
            val result = service.query(original)
            assertEquals("99064.6903981", result.remaining)
            assertEquals(ChannelQuotaKeyRef(welfare.id, "福利密钥"), result.queryKey)
            assertEquals(listOf("welfare-secret"), seen)
            val renamed = original.copy(keys = original.keys.map { if (it.id == welfare.id) it.copy(name = "共享福利") else it })
            val next = service.query(renamed)
            assertFalse(next.cached)
            assertEquals("共享福利", next.queryKey!!.name)
        }
    }

    @Test
    fun fallbackExceptionsKeepTheSourceKeyAndAttemptOrder() = runBlocking {
        val first = key("expired-secret", 1).copy(name = "过期密钥")
        val second = key("owned-secret", 2).copy(name = "自有密钥")
        withService(provider { request ->
            if (request.credential == "expired-secret") ChannelQuotaResult(ChannelQuotaStatus.AUTHENTICATION_FAILED)
            else error("owned-secret must stay private")
        }) { service ->
            val result = service.query(channel(keys = listOf(second, first)))
            assertEquals("ERROR", result.status)
            assertEquals(ChannelQuotaKeyRef(second.id, "自有密钥"), result.queryKey)
            assertEquals(listOf("过期密钥", "自有密钥"), result.attemptedKeys.map { it.name })
            assertFalse(result.toString().contains("owned-secret"))
            assertFalse(result.toString().contains("expired-secret"))
        }
    }

    @Test
    fun dedicatedCredentialWorksWithoutModelKeysAndKeepsItsSourceInCache() = runBlocking {
        val original = channel(keys = emptyList()).copy(
            quota = ChannelQuotaSettings("test", credentialSource = ChannelQuotaCredentialSource.DEDICATED),
            quotaCredential = cipher.encrypt("system-secret"),
        )
        val calls = AtomicInteger()
        withService(provider { request ->
            calls.incrementAndGet()
            assertEquals("system-secret", request.credential)
            ChannelQuotaResult(ChannelQuotaStatus.AVAILABLE, BigDecimal("100"), message = "system-secret")
        }) { service ->
            val result = service.query(original)
            assertEquals("100", result.remaining)
            assertEquals(ChannelQuotaCredentialSource.DEDICATED, result.credentialSource)
            assertNull(result.queryKey)
            assertTrue(result.attemptedKeys.isEmpty())
            assertFalse(result.toString().contains("system-secret"))
            val cached = service.query(original)
            assertTrue(cached.cached)
            assertEquals(result.credentialSource, cached.credentialSource)
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun missingOrInvalidDedicatedCredentialsNeverFallBackToModelKeys() = runBlocking {
        val original = channel().copy(quota = ChannelQuotaSettings("test", credentialSource = ChannelQuotaCredentialSource.DEDICATED))
        val seen = mutableListOf<String>()
        withService(provider { request ->
            seen += request.credential
            ChannelQuotaResult(ChannelQuotaStatus.AUTHENTICATION_FAILED)
        }) { service ->
            val missing = service.query(original)
            assertEquals("ERROR", missing.status)
            assertNull(missing.credentialSource)
            assertTrue(seen.isEmpty())
            val failed = service.query(original.copy(quotaCredential = cipher.encrypt("expired-system")))
            assertEquals("AUTHENTICATION_FAILED", failed.status)
            assertEquals(listOf("expired-system"), seen)
            assertEquals(ChannelQuotaCredentialSource.DEDICATED, failed.credentialSource)
            assertNull(failed.queryKey)
            assertTrue(failed.attemptedKeys.isEmpty())
        }
    }

    @Test
    fun rotatingDedicatedCredentialOrChangingSourceInvalidatesTheCache() = runBlocking {
        val original = channel().copy(
            quota = ChannelQuotaSettings("test", credentialSource = ChannelQuotaCredentialSource.DEDICATED),
            quotaCredential = cipher.encrypt("system-one"),
        )
        val seen = mutableListOf<String>()
        withService(provider { request -> seen += request.credential; available("10") }) { service ->
            service.query(original)
            assertFalse(service.query(original.copy(quotaCredential = cipher.encrypt("system-two"))).cached)
            val modelMode = service.query(original.copy(quota = ChannelQuotaSettings("test")))
            assertEquals(ChannelQuotaCredentialSource.CHANNEL_KEY, modelMode.credentialSource)
            assertNotNull(modelMode.queryKey)
            assertEquals(listOf("system-one", "system-two", "secret"), seen)
        }
    }

    @Test
    fun eachSelectedKeyUsesItsOwnAccountConfigCredentialAndCache() = runBlocking {
        fun configured(secret: String, userId: String) = key("model-$userId").copy(
            quota = ChannelQuotaSettings("test", Json.parseToJsonElement("{\"userId\":\"$userId\"}").jsonObject,
                ChannelQuotaCredentialSource.DEDICATED),
            quotaCredential = cipher.encrypt(secret),
        )
        val first = configured("system-first", "101")
        val second = configured("system-second", "202")
        val original = channel(keys = listOf(first, second)).copy(
            quota = ChannelQuotaSettings("test", credentialSource = ChannelQuotaCredentialSource.DEDICATED),
            quotaCredential = cipher.encrypt("legacy-account"),
        )
        val seen = mutableListOf<ChannelQuotaQuery>()
        withService(provider { request ->
            seen += request
            available(when (request.credential) { "system-first" -> "11"; "system-second" -> "22"; else -> "99" })
        }) { service ->
            val a = service.query(original, first.id)
            val b = service.query(original, second.id)
            assertEquals("11", a.remaining)
            assertEquals("22", b.remaining)
            assertEquals("KEY", a.scope)
            assertEquals(first.id, a.targetKeyId)
            assertEquals(second.id, b.targetKeyId)
            assertEquals(ChannelQuotaCredentialSource.DEDICATED, a.credentialSource)
            assertNull(a.queryKey)
            assertTrue(a.attemptedKeys.isEmpty())
            assertEquals(first.quota!!.config.toString(), seen[0].configJson)
            assertEquals(second.quota!!.config.toString(), seen[1].configJson)
            assertTrue(service.query(original, first.id).cached)
            assertTrue(service.query(original, second.id).cached)
            assertEquals(2, seen.size)
            assertEquals("99", service.query(original).remaining)
            assertEquals(listOf("system-first", "system-second", "legacy-account"), seen.map { it.credential })
            val rotated = original.copy(keys = listOf(first.copy(quotaCredential = cipher.encrypt("rotated")), second), updatedAt = 123)
            assertFalse(service.query(rotated, first.id).cached)
            // 更换 A 的账户凭据，不使 B 的目标缓存失效。
            assertTrue(service.query(rotated, second.id).cached)
        }
    }

    @Test
    fun selectedModelKeyNeverFallsBackToOtherAccountsEvenWhenDisabledOrUnauthorized() = runBlocking {
        val selected = key("selected-model").copy(quota = ChannelQuotaSettings("test"), state = ChannelKeyState.MANUAL_DISABLED)
        val other = key("other-account").copy(quota = ChannelQuotaSettings("test"))
        val original = channel(listOf(other, selected))
        val seen = mutableListOf<String>()
        withService(provider { request ->
            seen += request.credential
            ChannelQuotaResult(ChannelQuotaStatus.AUTHENTICATION_FAILED)
        }) { service ->
            val failed = service.query(original, selected.id)
            assertEquals("AUTHENTICATION_FAILED", failed.status)
            assertEquals(selected.id, failed.targetKeyId)
            assertEquals(selected.id, failed.queryKey!!.id)
            assertEquals(listOf(selected.id), failed.attemptedKeys.map { it.id })
            assertEquals(listOf("selected-model"), seen)
            assertEquals(ChannelKeyState.MANUAL_DISABLED, original.keys.last().state)
        }
    }

    @Test
    fun unconfiguredOrMissingKeyAccountCredentialsDoNotInheritTheChannelAccount() = runBlocking {
        val selected = key("selected")
        val original = channel(listOf(selected)).copy(
            quota = ChannelQuotaSettings("test", credentialSource = ChannelQuotaCredentialSource.DEDICATED),
            quotaCredential = cipher.encrypt("legacy-account"),
        )
        withService(provider { error("must not call any account") }) { service ->
            val unconfigured = service.query(original, selected.id)
            assertEquals("DISABLED", unconfigured.status)
            assertEquals(selected.id, unconfigured.targetKeyId)
            assertNull(unconfigured.credentialSource)
            val missing = service.query(original.copy(keys = listOf(selected.copy(
                quota = ChannelQuotaSettings("test", credentialSource = ChannelQuotaCredentialSource.DEDICATED),
            ))), selected.id)
            assertEquals("ERROR", missing.status)
            assertTrue(missing.message!!.contains("尚未设置"))
            assertNull(missing.credentialSource)
            assertFailsWith<IllegalArgumentException> { service.query(original, "unrelated-key") }
        }
    }

    @Test
    fun selectedKeyDedicatedAuthenticationFailurePreservesBindingAndDoesNotTryAnyModelKey() = runBlocking {
        val selected = key("model-secret").copy(
            quota = ChannelQuotaSettings("test", credentialSource = ChannelQuotaCredentialSource.DEDICATED),
            quotaCredential = cipher.encrypt("expired-system"),
        )
        val seen = mutableListOf<String>()
        withService(provider { request -> seen += request.credential; ChannelQuotaResult(ChannelQuotaStatus.AUTHENTICATION_FAILED) }) { service ->
            val result = service.query(channel(listOf(selected, key("another-model"))), selected.id)
            assertEquals("AUTHENTICATION_FAILED", result.status)
            assertEquals("KEY", result.scope)
            assertEquals(selected.id, result.targetKeyId)
            assertEquals(ChannelQuotaCredentialSource.DEDICATED, result.credentialSource)
            assertEquals(listOf("expired-system"), seen)
            assertNull(result.queryKey)
            assertTrue(result.attemptedKeys.isEmpty())
        }
    }

    private fun channel(keys: List<ChannelKeyRecord> = listOf(key("secret"))) = ChannelRecord(
        id = "channel", name = "Upstream", baseUrl = "https://upstream.example/v1", authType = "bearer",
        protocols = GatewayProtocol.default, enabled = true, createdAt = 0, updatedAt = 0,
        keys = keys, quota = ChannelQuotaSettings("test"),
    )

    private fun key(secret: String, priority: Int = 10): ChannelKeyRecord {
        val encrypted = cipher.encrypt(secret)
        return ChannelKeyRecord(id = java.util.UUID.nameUUIDFromBytes(secret.toByteArray()).toString(), channelId = "channel", name = secret,
            credentialCiphertext = encrypted.ciphertext, credentialNonce = encrypted.nonce,
            credentialKeyVersion = encrypted.keyVersion, state = ChannelKeyState.ACTIVE, priority = priority,
            createdAt = 0, updatedAt = 0)
    }

    private fun available(value: String) = ChannelQuotaResult(ChannelQuotaStatus.AVAILABLE, BigDecimal(value), unit = "USD")

    private fun provider(action: (ChannelQuotaQuery) -> ChannelQuotaResult) = object : ChannelQuotaProvider {
        override val id = "test"
        override fun query(request: ChannelQuotaQuery) = action(request)
    }

    private suspend fun withService(provider: ChannelQuotaProvider, now: () -> Long = System::currentTimeMillis,
        timeoutMs: Long = 45_000, action: suspend (ChannelQuotaService) -> Unit) {
        ChannelQuotaProviderRegistry(builtIns = listOf(provider)).use { registry ->
            ChannelQuotaService(registry, cipher, credentials, now, timeoutMs).use { action(it) }
        }
    }
}
