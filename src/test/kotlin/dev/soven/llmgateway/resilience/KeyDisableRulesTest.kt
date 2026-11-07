package dev.soven.llmgateway.resilience

import dev.soven.llmgateway.domain.ChannelKeyRecord
import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.domain.GatewayProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyDisableRulesTest {
    private val now = 1_700_000_000_000L

    private fun rule(
        id: String = "r1",
        statusCodes: List<Int> = listOf(401),
        statusCodeAtLeast: Int? = null,
        bodyPatterns: List<String> = emptyList(),
        threshold: Int = 2,
        windowMinutes: Int = 5,
        streak: Boolean = true,
        action: KeyDisableAction = KeyDisableAction.PERMANENT,
        disableMinutes: Int? = null,
        includeTransportFailures: Boolean = false,
    ) = KeyDisableRule(
        id = id,
        name = "规则 $id",
        statusCodes = statusCodes,
        statusCodeAtLeast = statusCodeAtLeast,
        bodyPatterns = bodyPatterns,
        threshold = threshold,
        windowMinutes = windowMinutes,
        streak = streak,
        action = action,
        disableMinutes = disableMinutes,
        includeTransportFailures = includeTransportFailures,
    )

    private fun response(code: Int, body: String = "") = KeyAttemptSignal.UpstreamResponse(code, body)

    @Test
    fun `status code match triggers only at threshold`() {
        val policy = KeyDisablePolicy(rules = listOf(rule(threshold = 2)))
        val first = policy.record(KeyHealthState(), response(401), now)
        assertNull(first.decision)
        assertEquals(1, first.state.counters.getValue("r1").timestamps.size)

        val second = policy.record(first.state, response(401), now + 1)
        val decision = assertNotNull(second.decision)
        assertEquals("r1", decision.ruleId)
        assertEquals(2, decision.count)
        assertEquals(KeyDisableAction.PERMANENT, decision.action)
        assertNull(decision.disableUntil)
    }

    @Test
    fun `unmatched status code does not count`() {
        val policy = KeyDisablePolicy(rules = listOf(rule(threshold = 1)))
        assertNull(policy.record(KeyHealthState(), response(400), now).decision)
        assertNull(policy.record(KeyHealthState(), response(500), now).decision)
    }

    @Test
    fun `statusCodeAtLeast covers every server error`() {
        val policy = KeyDisablePolicy(
            rules = listOf(rule(statusCodes = emptyList(), statusCodeAtLeast = 500, threshold = 1)),
        )
        assertNull(policy.record(KeyHealthState(), response(499), now).decision)
        assertNotNull(policy.record(KeyHealthState(), response(500), now).decision)
        assertNotNull(policy.record(KeyHealthState(), response(503), now).decision)
    }

    @Test
    fun `transport failures only count for rules that opt in`() {
        val withTransport = KeyDisablePolicy(
            rules = listOf(rule(statusCodes = listOf(429), includeTransportFailures = true, threshold = 1)),
        )
        assertNotNull(withTransport.record(KeyHealthState(), KeyAttemptSignal.TransportFailure("connect timeout"), now).decision)

        val withoutTransport = KeyDisablePolicy(rules = listOf(rule(statusCodes = listOf(429), threshold = 1)))
        assertNull(withoutTransport.record(KeyHealthState(), KeyAttemptSignal.TransportFailure(), now).decision)
    }

    @Test
    fun `transport failure text must match configured patterns`() {
        val policy = KeyDisablePolicy(
            rules = listOf(
                rule(
                    statusCodes = listOf(429),
                    bodyPatterns = listOf("timeout"),
                    includeTransportFailures = true,
                    threshold = 1,
                ),
            ),
        )
        assertNull(policy.record(KeyHealthState(), KeyAttemptSignal.TransportFailure("connection refused"), now).decision)
        val hit = assertNotNull(
            policy.record(KeyHealthState(), KeyAttemptSignal.TransportFailure("read timeout"), now).decision,
        )
        assertEquals("timeout", hit.matchedPattern)
    }

    @Test
    fun `status code union and body pattern are intersected`() {
        val policy = KeyDisablePolicy(
            rules = listOf(
                rule(
                    statusCodes = listOf(429),
                    statusCodeAtLeast = 500,
                    bodyPatterns = listOf("rate_limit"),
                    threshold = 1,
                ),
            ),
        )
        assertNull(policy.record(KeyHealthState(), response(500, "server down"), now).decision)
        assertNull(policy.record(KeyHealthState(), response(418, "rate_limit"), now).decision)
        assertNotNull(policy.record(KeyHealthState(), response(503, "rate_limit exceeded"), now).decision)
    }

    @Test
    fun `body pattern narrows a matching status code`() {
        val policy = KeyDisablePolicy(
            rules = listOf(rule(bodyPatterns = listOf("invalid_api_key"), threshold = 1)),
        )
        assertNull(policy.record(KeyHealthState(), response(401, "model not enabled"), now).decision)
        assertNull(policy.record(KeyHealthState(), response(403, "region not supported"), now).decision)
        val hit = assertNotNull(
            policy.record(KeyHealthState(), response(401, "{\"error\":{\"code\":\"invalid_api_key\"}}"), now).decision,
        )
        assertEquals("invalid_api_key", hit.matchedPattern)
    }

    @Test
    fun `patterns are case insensitive`() {
        val policy = KeyDisablePolicy(
            rules = listOf(rule(bodyPatterns = listOf("Incorrect API Key"), threshold = 1)),
        )
        assertNotNull(policy.record(KeyHealthState(), response(401, "INCORRECT api key provided"), now).decision)
    }

    @Test
    fun `first matching rule wins and later rules never count`() {
        val policy = KeyDisablePolicy(
            rules = listOf(
                rule(id = "specific", bodyPatterns = listOf("invalid_api_key"), threshold = 1),
                rule(id = "fallback", threshold = 1),
            ),
        )
        val decision = assertNotNull(
            policy.record(KeyHealthState(), response(401, "invalid_api_key"), now).decision,
        )
        assertEquals("specific", decision.ruleId)
    }

    @Test
    fun `streak rule resets on success while window rule keeps failures`() {
        val streakPolicy = KeyDisablePolicy(rules = listOf(rule(threshold = 2)))
        val once = streakPolicy.record(KeyHealthState(), response(401), now)
        val reset = streakPolicy.record(once.state, KeyAttemptSignal.Success, now + 1)
        assertTrue(reset.state.counters.isEmpty())
        assertNull(streakPolicy.record(reset.state, response(401), now + 2).decision)

        val windowPolicy = KeyDisablePolicy(rules = listOf(rule(threshold = 3, streak = false)))
        var state = windowPolicy.record(KeyHealthState(), response(401), now).state
        state = windowPolicy.record(state, response(401), now + 1).state
        state = windowPolicy.record(state, KeyAttemptSignal.Success, now + 2).state
        assertEquals(2, state.counters.getValue("r1").timestamps.size)
        assertNotNull(windowPolicy.record(state, response(401), now + 3).decision)
    }

    @Test
    fun `unmatched upstream responses do not reset the streak`() {
        // 400 来自调用方请求内容，不能证明凭据可用；否则可用无效请求掩盖一把坏钥匙。
        val policy = KeyDisablePolicy(rules = listOf(rule(threshold = 2)))
        val once = policy.record(KeyHealthState(), response(401), now)
        val other = policy.record(once.state, response(400, "invalid request"), now + 1)
        assertEquals(1, other.state.counters.getValue("r1").timestamps.size)
        assertNotNull(policy.record(other.state, response(401), now + 2).decision)
    }

    @Test
    fun `ignored outcomes neither count nor reset the streak`() {
        val policy = KeyDisablePolicy(rules = listOf(rule(threshold = 2)))
        val once = policy.record(KeyHealthState(), response(401), now)
        val ignored = policy.record(once.state, KeyAttemptSignal.Ignored, now + 1)
        assertEquals(1, ignored.state.counters.getValue("r1").timestamps.size)
        assertNotNull(policy.record(ignored.state, response(401), now + 2).decision)
    }

    @Test
    fun `failures outside the window are dropped`() {
        val policy = KeyDisablePolicy(rules = listOf(rule(threshold = 2, windowMinutes = 5)))
        val first = policy.record(KeyHealthState(), response(401), now)
        val later = policy.record(first.state, response(401), now + 5 * 60_000L)
        assertNull(later.decision)
        assertEquals(listOf(now + 5 * 60_000L), later.state.counters.getValue("r1").timestamps)
    }

    @Test
    fun `temporary rule carries an absolute expiry`() {
        val policy = KeyDisablePolicy(
            rules = listOf(rule(threshold = 1, action = KeyDisableAction.TEMPORARY, disableMinutes = 30)),
        )
        val decision = assertNotNull(policy.record(KeyHealthState(), response(401), now).decision)
        assertEquals(now + 30 * 60_000L, decision.disableUntil)
    }

    @Test
    fun `disabled policy forgets every counter`() {
        val policy = KeyDisablePolicy(enabled = false, rules = listOf(rule(threshold = 1)))
        val update = policy.record(KeyHealthState(mapOf("r1" to KeyRuleCounter(listOf(now - 1)))), response(401), now)
        assertTrue(update.state.counters.isEmpty())
        assertNull(update.decision)
    }

    @Test
    fun `disabled policy needs no rules because nothing runs`() {
        val policy = KeyDisablePolicy(enabled = false, rules = emptyList())
        assertNull(policy.record(KeyHealthState(), response(401), now).decision)
        // 只有关闭时允许空规则；启用状态空规则等于静默失效，构造时就拒绝。
        assertFailsWith<IllegalArgumentException> { KeyDisablePolicy(enabled = true, rules = emptyList()) }
    }

    @Test
    fun `channel rules run first and fall back to global rules on a miss`() {
        val channelRule = rule(id = "activity_disabled", statusCodes = listOf(403), bodyPatterns = listOf("ACTIVITY_DISABLED"), threshold = 1)
        val globalRule = rule(id = "credential_invalid", statusCodes = listOf(401), threshold = 1)
        val merged = KeyDisablePolicy(rules = listOf(channelRule)).fallbackTo(KeyDisablePolicy(rules = listOf(globalRule)))

        assertEquals(listOf("activity_disabled", "credential_invalid"), merged.rules.map { it.id })
        assertEquals(
            "activity_disabled",
            assertNotNull(merged.record(KeyHealthState(), response(403, "ACTIVITY_DISABLED"), now).decision).ruleId,
        )
        // 渠道规则未命中（状态码不匹配）时回落全局规则。
        assertEquals(
            "credential_invalid",
            assertNotNull(merged.record(KeyHealthState(), response(401, ""), now).decision).ruleId,
        )
    }

    @Test
    fun `a channel rule that matches consumes the signal even below its threshold`() {
        // 合并后仍是首条命中即止：渠道规则命中但未达阈值时不能继续落到全局规则，
        // 否则同一次失败会被两套规则各记一次。
        val channelRule = rule(id = "channel_soft", statusCodes = listOf(401), threshold = 5)
        val globalRule = rule(id = "global_hard", statusCodes = listOf(401), threshold = 1)
        val merged = KeyDisablePolicy(rules = listOf(channelRule)).fallbackTo(KeyDisablePolicy(rules = listOf(globalRule)))

        val update = merged.record(KeyHealthState(), response(401), now)
        assertNull(update.decision)
        assertEquals(setOf("channel_soft"), update.state.counters.keys)
    }

    @Test
    fun `same id global rules are replaced by the channel version`() {
        val channelRule = rule(id = "shared", statusCodes = listOf(403), threshold = 2)
        val globalRule = rule(id = "shared", statusCodes = listOf(401), threshold = 1)
        val merged = KeyDisablePolicy(rules = listOf(channelRule)).fallbackTo(KeyDisablePolicy(rules = listOf(globalRule)))
        assertEquals(listOf("shared"), merged.rules.map { it.id })
        // 只保留渠道版本：401 不再匹配，403 按渠道阈值 2 计数。
        assertNull(merged.record(KeyHealthState(), response(401), now).decision)
        val first = merged.record(KeyHealthState(), response(403), now)
        assertNull(first.decision)
        assertNotNull(merged.record(first.state, response(403), now + 1).decision)
    }

    @Test
    fun `a disabled channel override suppresses the global rules too`() {
        val disabled = KeyDisablePolicy(enabled = false, rules = emptyList())
        val merged = disabled.fallbackTo(KeyDisablePolicy(rules = listOf(rule(id = "global", threshold = 1))))
        assertFalse(merged.enabled)
        assertTrue(merged.rules.isEmpty())
        assertNull(merged.record(KeyHealthState(), response(401), now).decision)
    }

    @Test
    fun `a channel override does not resurrect rules when global auto disable is off`() {
        val channel = KeyDisablePolicy(rules = listOf(rule(id = "channel", threshold = 1)))
        val merged = channel.fallbackTo(KeyDisablePolicy(enabled = false, rules = listOf(rule(id = "global", threshold = 1))))
        assertEquals(listOf("channel"), merged.rules.map { it.id })
    }

    @Test
    fun `channel mode maps the three persisted shapes`() {
        assertEquals(ChannelKeyDisableMode.INHERIT, ChannelKeyDisableMode.of(null))
        assertEquals(ChannelKeyDisableMode.CUSTOM, ChannelKeyDisableMode.of(KeyDisablePolicy(rules = listOf(rule()))))
        assertEquals(
            ChannelKeyDisableMode.DISABLED,
            ChannelKeyDisableMode.of(KeyDisablePolicy(enabled = false, rules = emptyList())),
        )
        assertEquals("inherit", ChannelKeyDisableMode.INHERIT.value)
        assertEquals("custom", ChannelKeyDisableMode.CUSTOM.value)
        assertEquals("disabled", ChannelKeyDisableMode.DISABLED.value)
    }

    @Test
    fun `fingerprint changes with scope or effective rules and is stable otherwise`() {
        val ruleA = rule(id = "a", threshold = 1)
        val base = ResolvedKeyDisablePolicy("global", 1, KeyDisablePolicy(rules = listOf(ruleA)))
        val same = ResolvedKeyDisablePolicy("global", 7, KeyDisablePolicy(rules = listOf(ruleA)))
        val otherScope = ResolvedKeyDisablePolicy("channel:c1", 1, KeyDisablePolicy(rules = listOf(ruleA)))
        val otherRule = ResolvedKeyDisablePolicy("global", 1, KeyDisablePolicy(rules = listOf(ruleA.copy(name = "改名"))))

        // 版本号变化但内容相同：计数仍然有效。
        assertEquals(base.fingerprint, same.fingerprint)
        // 作用域或内容变化必须换指纹，否则旧计数会触发新规则。
        assertTrue(base.fingerprint != otherScope.fingerprint)
        assertTrue(base.fingerprint != otherRule.fingerprint)
        assertTrue(base.fingerprint >= 0)
    }

    @Test
    fun `channel override codec round trips and treats garbage as no override`() {
        val policy = KeyDisablePolicy(rules = listOf(rule(id = "activity_disabled", statusCodes = listOf(403), threshold = 1)))
        assertEquals(policy, ChannelKeyDisablePolicyCodec.decode(ChannelKeyDisablePolicyCodec.encode(policy)))
        assertNull(ChannelKeyDisablePolicyCodec.decode(null))
        assertNull(ChannelKeyDisablePolicyCodec.decode(""))
        assertNull(ChannelKeyDisablePolicyCodec.decode("  "))
        assertNull(ChannelKeyDisablePolicyCodec.decode("{not json"))
        // 关闭形态的空规则列表必须能重新读出来，否则重启就变成继承全局。
        val disabled = KeyDisablePolicy(enabled = false, rules = emptyList())
        assertEquals(disabled, ChannelKeyDisablePolicyCodec.decode(ChannelKeyDisablePolicyCodec.encode(disabled)))
    }

    @Test
    fun `default rules keep ADR 0012 neutral status codes and permanent balance`() {
        val policy = KeyDisablePolicy()
        // 400/404/422 是调用方请求问题：不计入任何规则，也不打断连续计数。
        // credential_invalid 是连续计数、阈值 2：一次 401 后未被中性响应打断，才该在第二次 401 触发。
        var state = policy.record(KeyHealthState(), response(401, "invalid_api_key"), now).state
        assertEquals(1, state.counters.getValue("credential_invalid").timestamps.size)
        for (code in listOf(400, 404, 422)) {
            val update = policy.record(state, response(code, "invalid_api_key"), now + code)
            assertEquals(1, update.state.counters.getValue("credential_invalid").timestamps.size, "HTTP $code 不应清掉连续计数")
            assertNull(update.decision)
            state = update.state
        }
        assertEquals(
            "credential_invalid",
            assertNotNull(policy.record(state, response(401, "invalid_api_key"), now + 1_000).decision).ruleId,
        )
        // 余额不足是终态：第一次命中就永久停用，不能配成临时停用。
        val balance = assertNotNull(
            policy.record(KeyHealthState(), response(402, "INSUFFICIENT_BALANCE"), now).decision,
        )
        assertEquals("insufficient_balance", balance.ruleId)
        assertEquals(KeyDisableAction.PERMANENT, balance.action)
    }

    @Test
    fun `removed rules drop their counters`() {
        // 窗口规则的成功不清零，因此这里只剩「已下线规则的计数被丢弃」这一个变量。
        val policy = KeyDisablePolicy(rules = listOf(rule(id = "current", threshold = 5, streak = false)))
        val stale = KeyHealthState(
            mapOf(
                "removed" to KeyRuleCounter(listOf(now - 1)),
                "current" to KeyRuleCounter(listOf(now - 1)),
            ),
        )
        val update = policy.record(stale, KeyAttemptSignal.Success, now)
        assertEquals(setOf("current"), update.state.counters.keys)
        assertEquals(listOf(now - 1), update.state.counters.getValue("current").timestamps)
    }

    @Test
    fun `success clears streak counters but keeps window counters`() {
        val policy = KeyDisablePolicy(
            rules = listOf(
                rule(id = "streak", threshold = 5),
                rule(id = "window", threshold = 5, streak = false),
            ),
        )
        val state = KeyHealthState(
            mapOf(
                "streak" to KeyRuleCounter(listOf(now - 1)),
                "window" to KeyRuleCounter(listOf(now - 1)),
            ),
        )
        val update = policy.record(state, KeyAttemptSignal.Success, now)
        assertEquals(setOf("window"), update.state.counters.keys)
    }

    @Test
    fun `invalid rules are rejected when constructed`() {
        assertFailsWith<IllegalArgumentException> { rule(id = "Bad-Id") }
        assertFailsWith<IllegalArgumentException> { rule(threshold = 0) }
        assertFailsWith<IllegalArgumentException> { rule(windowMinutes = 0) }
        assertFailsWith<IllegalArgumentException> { rule(statusCodes = emptyList()) }
        assertFailsWith<IllegalArgumentException> { rule(statusCodes = listOf(401, 401)) }
        assertFailsWith<IllegalArgumentException> { rule(action = KeyDisableAction.TEMPORARY) }
        assertFailsWith<IllegalArgumentException> {
            rule(action = KeyDisableAction.TEMPORARY, disableMinutes = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            rule(action = KeyDisableAction.TEMPORARY, disableMinutes = 10_081)
        }
        assertFailsWith<IllegalArgumentException> {
            rule(action = KeyDisableAction.PERMANENT, disableMinutes = 5)
        }
        assertFailsWith<IllegalArgumentException> { rule(bodyPatterns = listOf("(?<=x)y")) }
        assertFailsWith<IllegalArgumentException> { rule(bodyPatterns = listOf("a".repeat(201))) }
        assertFailsWith<IllegalArgumentException> {
            KeyDisablePolicy(rules = listOf(rule(id = "dup"), rule(id = "dup")))
        }
        assertFailsWith<IllegalArgumentException> {
            KeyDisablePolicy(rules = (1..51).map { rule(id = "r$it") })
        }
    }

    @Test
    fun `disable status availability follows the four states`() {
        assertTrue(KeyDisableStatus().isAvailable(now))
        assertFalse(KeyDisableStatus(KeyDisableKind.MANUAL).isAvailable(now))
        assertFalse(KeyDisableStatus(KeyDisableKind.PERMANENT).isAvailable(now))

        val temporary = KeyDisableStatus(KeyDisableKind.TEMPORARY, until = now + 1)
        assertFalse(temporary.isAvailable(now))
        assertTrue(temporary.isAvailable(now + 1))
        assertTrue(temporary.autoRecoverable)
        assertFalse(KeyDisableStatus(KeyDisableKind.PERMANENT).autoRecoverable)
        assertFailsWith<IllegalArgumentException> { KeyDisableStatus(KeyDisableKind.TEMPORARY) }
        assertFailsWith<IllegalArgumentException> { KeyDisableStatus(KeyDisableKind.MANUAL, until = now) }
    }

    @Test
    fun `default rules separate permanent credentials from temporary instability`() {
        val policy = KeyDisablePolicy()
        var state = KeyHealthState()
        repeat(2) { index ->
            val update = policy.record(state, response(401, "invalid_api_key"), now + index)
            state = update.state
            if (index == 0) assertNull(update.decision)
            else assertEquals(KeyDisableAction.PERMANENT, assertNotNull(update.decision).action)
        }

        val insufficientBalance = assertNotNull(
            policy.record(
                KeyHealthState(),
                response(403, """{"error":{"type":"authentication_error","code":"INSUFFICIENT_BALANCE","message":"Insufficient account balance"}}"""),
                now,
            ).decision,
        )
        assertEquals("insufficient_balance", insufficientBalance.ruleId)
        assertEquals(KeyDisableAction.PERMANENT, insufficientBalance.action)
        assertEquals(1, insufficientBalance.count)

        val temporary = assertNotNull(
            policy.record(KeyHealthState(), response(401, "model not enabled for this key"), now).state,
        )
        assertEquals(1, temporary.counters.getValue("auth_rejected").timestamps.size)

        val unstable = KeyDisablePolicy().record(
            KeyHealthState(), KeyAttemptSignal.TransportFailure("read timeout"), now,
        )
        assertEquals(1, unstable.state.counters.getValue("upstream_unstable").timestamps.size)

        assertNull(policy.record(KeyHealthState(), response(400), now).decision)
        assertNull(policy.record(KeyHealthState(), response(404), now).decision)
        assertNull(policy.record(KeyHealthState(), response(422), now).decision)
    }

    private fun testKey(
        id: String = "k1",
        name: String = "k1",
        state: ChannelKeyState = ChannelKeyState.ACTIVE,
        priority: Int = 1,
        models: List<String> = listOf("m1"),
        autoDisableUntil: Long? = null,
        probeUntil: Long? = null,
    ) = ChannelKeyRecord(
        id = id,
        channelId = "c1",
        name = name,
        credentialCiphertext = "cipher",
        credentialNonce = "nonce",
        credentialKeyVersion = 1,
        state = state,
        priority = priority,
        models = models,
        createdAt = 0,
        updatedAt = 0,
        autoDisableUntil = autoDisableUntil,
        probeUntil = probeUntil,
    )

    @Test
    fun `ChannelKeyRecord isRoutable honors four states and expiry boundary`() {
        val active = testKey(state = ChannelKeyState.ACTIVE)
        assertTrue(active.isRoutable(now))
        assertTrue(active.enabled)
        assertFalse(active.autoRecoverable)

        val manual = testKey(state = ChannelKeyState.MANUAL_DISABLED)
        assertFalse(manual.isRoutable(now))
        assertFalse(manual.enabled)
        assertFalse(manual.autoRecoverable)

        val permanent = testKey(state = ChannelKeyState.AUTO_PERMANENT)
        assertFalse(permanent.isRoutable(now))
        assertFalse(permanent.enabled)
        assertFalse(permanent.autoRecoverable)

        val temporaryWithoutUntil = testKey(state = ChannelKeyState.AUTO_TEMPORARY, autoDisableUntil = null)
        assertFalse(temporaryWithoutUntil.isRoutable(now))
        assertTrue(temporaryWithoutUntil.autoRecoverable)
        assertFalse(temporaryWithoutUntil.enabled)

        val temporary = testKey(state = ChannelKeyState.AUTO_TEMPORARY, autoDisableUntil = now + 100)
        assertFalse(temporary.isRoutable(now))
        assertFalse(temporary.isRoutable(now + 99))
        assertTrue(temporary.isRoutable(now + 100), "到期整点即视作可用")
        assertTrue(temporary.isRoutable(now + 101))

        val probing = testKey(
            state = ChannelKeyState.AUTO_TEMPORARY,
            autoDisableUntil = now,
            probeUntil = now + 30,
        )
        assertFalse(probing.isRoutable(now), "探测租约期间其他请求不得再路由")
        assertFalse(probing.isRoutable(now + 29))
        assertTrue(probing.isRoutable(now + 30), "租约到期后允许下一次探测")
    }

    @Test
    fun `ChannelKeyRecord recoveryAt reports when a temporary key becomes routable again`() {
        assertNull(testKey(state = ChannelKeyState.ACTIVE).recoveryAt(now), "正常密钥没有等待恢复一说")
        assertNull(testKey(state = ChannelKeyState.MANUAL_DISABLED).recoveryAt(now), "人工停用不会自行恢复")
        assertNull(testKey(state = ChannelKeyState.AUTO_PERMANENT).recoveryAt(now), "永久停用不会自行恢复")
        assertNull(
            testKey(state = ChannelKeyState.AUTO_TEMPORARY, autoDisableUntil = null).recoveryAt(now),
            "缺少到期时间时无法给出恢复时间",
        )

        val temporary = testKey(state = ChannelKeyState.AUTO_TEMPORARY, autoDisableUntil = now + 100)
        assertEquals(now + 100, temporary.recoveryAt(now))
        assertEquals(now + 100, temporary.recoveryAt(now + 99))
        assertNull(temporary.recoveryAt(now + 100), "已到期即视为可路由，不再是等待恢复")

        // 租约比停用到期更晚时，恢复时间必须取较晚者，否则 Retry-After 会早于真实可用时间。
        val leased = testKey(
            state = ChannelKeyState.AUTO_TEMPORARY,
            autoDisableUntil = now + 10,
            probeUntil = now + 50,
        )
        assertEquals(now + 50, leased.recoveryAt(now))
        // 停用已到期、租约未到期：仍要给出重试提示，而不是当作已恢复。
        val leaseOnly = testKey(
            state = ChannelKeyState.AUTO_TEMPORARY,
            autoDisableUntil = now - 60,
            probeUntil = now + 40,
        )
        assertEquals(now + 40, leaseOnly.recoveryAt(now))
        assertNull(leaseOnly.recoveryAt(now + 40))
    }

    @Test
    fun `ChannelRecord keysFor filters unroutable keys and sorts by priority name id`() {
        val k1 = testKey(id = "1", name = "z", priority = 2, state = ChannelKeyState.ACTIVE, models = listOf("m1"))
        val k2 = testKey(id = "2", name = "b", priority = 1, state = ChannelKeyState.ACTIVE, models = listOf("m1"))
        val k3 = testKey(id = "3", name = "a", priority = 1, state = ChannelKeyState.ACTIVE, models = listOf("m1"))
        val k4 = testKey(id = "4", name = "a0", priority = 1, state = ChannelKeyState.MANUAL_DISABLED, models = listOf("m1"))
        val k5 = testKey(id = "5", name = "a1", priority = 1, state = ChannelKeyState.AUTO_TEMPORARY, autoDisableUntil = now + 500, models = listOf("m1"))
        val k6 = testKey(id = "6", name = "a2", priority = 1, state = ChannelKeyState.ACTIVE, models = listOf("other"))

        val channel = ChannelRecord(
            id = "c1",
            name = "channel",
            baseUrl = "https://example.com/v1",
            authType = "bearer",
            protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS),
            enabled = true,
            createdAt = 0,
            updatedAt = 0,
            keys = listOf(k1, k2, k3, k4, k5, k6),
        )

        val beforeExpiry = channel.keysFor("m1", now)
        assertEquals(listOf("3", "2", "1"), beforeExpiry.map { it.id })

        val afterExpiry = channel.keysFor("m1", now + 500)
        assertEquals(listOf("3", "5", "2", "1"), afterExpiry.map { it.id })
    }

    @Test
    fun `ChannelKeyState mapping and properties`() {
        assertTrue(ChannelKeyState.ACTIVE.manuallyAssignable)
        assertTrue(ChannelKeyState.MANUAL_DISABLED.manuallyAssignable)
        assertFalse(ChannelKeyState.AUTO_TEMPORARY.manuallyAssignable)
        assertFalse(ChannelKeyState.AUTO_PERMANENT.manuallyAssignable)

        assertFalse(ChannelKeyState.ACTIVE.autoDisabled)
        assertFalse(ChannelKeyState.MANUAL_DISABLED.autoDisabled)
        assertTrue(ChannelKeyState.AUTO_TEMPORARY.autoDisabled)
        assertTrue(ChannelKeyState.AUTO_PERMANENT.autoDisabled)

        for (state in ChannelKeyState.entries) {
            assertEquals(state, ChannelKeyState.fromKind(state.toKind()))
            assertEquals(state, ChannelKeyState.fromValue(state.value))
        }
    }
}
