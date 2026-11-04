package dev.soven.llmgateway.resilience

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * 一次实际上游尝试的结果。只携带规则匹配需要的原始信号，不含请求正文或凭据。
 *
 * @author 文杰
 * @since 2026-10-29
 */
sealed interface KeyAttemptSignal {
    /** 上游成功返回。 */
    data object Success : KeyAttemptSignal

    /** 客户端取消、网关本地限流或请求体错误：既不奖也不罚。 */
    data object Ignored : KeyAttemptSignal

    /** 上游返回 HTTP 错误；[errorBody] 由调用方截断后传入，[upstreamCode] 仅用于审计。 */
    data class UpstreamResponse(
        val statusCode: Int,
        val errorBody: String = "",
        val upstreamCode: String? = null,
    ) : KeyAttemptSignal

    /** 连接失败、超时、空流等传输层失败。 */
    data class TransportFailure(val message: String = "") : KeyAttemptSignal
}

/** 自动禁用动作。临时禁用带到期时间，永久禁用不会自动恢复，只能人工启用。 */
@Serializable
enum class KeyDisableAction { TEMPORARY, PERMANENT }

/** 密钥停用来源。人工停用永不被自动恢复任务改写。 */
enum class KeyDisableKind { NONE, MANUAL, TEMPORARY, PERMANENT }

/**
 * 密钥的持久化状态。只有 [AUTO_TEMPORARY] 会到期自动恢复，
 * [AUTO_PERMANENT] 不会自动恢复，需人工启用。
 *
 * @author 文杰
 * @since 2026-10-29
 */
@Serializable
enum class ChannelKeyState(val value: String) {
    ACTIVE("ACTIVE"),
    MANUAL_DISABLED("MANUAL_DISABLED"),
    AUTO_TEMPORARY("AUTO_TEMPORARY"),
    AUTO_PERMANENT("AUTO_PERMANENT"),
    ;

    /** 客户端只能提交这两种人工状态；自动状态只由系统写入。 */
    val manuallyAssignable: Boolean get() = this == ACTIVE || this == MANUAL_DISABLED

    val autoDisabled: Boolean get() = this == AUTO_TEMPORARY || this == AUTO_PERMANENT

    fun toKind(): KeyDisableKind = when (this) {
        ACTIVE -> KeyDisableKind.NONE
        MANUAL_DISABLED -> KeyDisableKind.MANUAL
        AUTO_TEMPORARY -> KeyDisableKind.TEMPORARY
        AUTO_PERMANENT -> KeyDisableKind.PERMANENT
    }

    companion object {
        fun fromValue(value: String?): ChannelKeyState? = entries.firstOrNull { it.value == value }

        fun fromKind(kind: KeyDisableKind): ChannelKeyState = when (kind) {
            KeyDisableKind.NONE -> ACTIVE
            KeyDisableKind.MANUAL -> MANUAL_DISABLED
            KeyDisableKind.TEMPORARY -> AUTO_TEMPORARY
            KeyDisableKind.PERMANENT -> AUTO_PERMANENT
        }
    }
}

/**
 * 一条自动禁用规则。规则按列表顺序匹配，首条命中生效，规则之间不叠加计数。
 *
 * 状态码与文本条件同时配置时取交集。[bodyPatterns] 使用 RE2 语法、大小写不敏感，
 * 构造时编译校验：RE2 不支持反向引用和前后瞻，非法表达式直接拒绝而不是运行期静默失效。
 *
 * @author 文杰
 * @since 2026-10-29
 */
@Serializable
data class KeyDisableRule(
    val id: String,
    val name: String,
    val statusCodes: List<Int> = emptyList(),
    val statusCodeAtLeast: Int? = null,
    val bodyPatterns: List<String> = emptyList(),
    val threshold: Int,
    val windowMinutes: Int = 5,
    val streak: Boolean = true,
    val action: KeyDisableAction,
    val disableMinutes: Int? = null,
    val includeTransportFailures: Boolean = false,
) {
    init {
        require(ID_PATTERN.matches(id)) { "规则 id 只能包含小写字母、数字和下划线，长度 1..40" }
        require(name.isNotBlank() && name.length <= 40) { "规则名称不能为空且不超过 40 个字符" }
        require(statusCodes.all { it in 100..599 }) { "状态码必须在 100..599 之间" }
        require(statusCodes.size == statusCodes.distinct().size) { "状态码不能重复" }
        require(statusCodeAtLeast == null || statusCodeAtLeast in 100..599) { "状态码下限必须在 100..599 之间" }
        require(statusCodes.isNotEmpty() || statusCodeAtLeast != null || includeTransportFailures) {
            "规则至少需要一个状态码、状态码下限或传输层条件"
        }
        require(threshold in 1..1000) { "触发阈值必须在 1..1000 之间" }
        require(windowMinutes in 1..1440) { "统计窗口必须在 1..1440 分钟之间" }
        require(bodyPatterns.size <= MAX_PATTERNS) { "单条规则最多 $MAX_PATTERNS 个匹配模式" }
        require(bodyPatterns.all { it.length in 1..MAX_PATTERN_LENGTH }) {
            "匹配模式长度必须在 1..$MAX_PATTERN_LENGTH 之间"
        }
        require(action == KeyDisableAction.PERMANENT || disableMinutes != null) { "临时禁用必须配置时长" }
        require(action == KeyDisableAction.TEMPORARY || disableMinutes == null) { "永久禁用不能配置时长" }
        require(disableMinutes == null || disableMinutes in 1..MAX_DISABLE_MINUTES) {
            "临时禁用时长必须在 1..$MAX_DISABLE_MINUTES 分钟之间"
        }
    }

    /** 构造时完成编译，保存非法规则会在管理面直接失败。 */
    @Transient
    private val compiledPatterns: List<Pattern> = bodyPatterns.mapIndexed(::compilePattern)

    /** 临时禁用到期后可自动恢复；永久禁用只能人工处理。 */
    val autoRecoverable: Boolean get() = action == KeyDisableAction.TEMPORARY

    /** 返回命中的模式；规则不匹配时返回 null。传输层失败没有错误体，文本条件作用于异常信息。 */
    internal fun match(signal: KeyAttemptSignal): KeyRuleMatch? = when (signal) {
        KeyAttemptSignal.Success, KeyAttemptSignal.Ignored -> null
        is KeyAttemptSignal.TransportFailure ->
            if (includeTransportFailures) matchText(signal.message) else null
        is KeyAttemptSignal.UpstreamResponse -> {
            val codeMatched = signal.statusCode in statusCodes ||
                (statusCodeAtLeast != null && signal.statusCode >= statusCodeAtLeast)
            if (codeMatched) matchText(signal.errorBody) else null
        }
    }

    private fun matchText(text: String): KeyRuleMatch? {
        if (compiledPatterns.isEmpty()) return KeyRuleMatch(null)
        if (text.isEmpty()) return null
        val hit = compiledPatterns.firstOrNull { it.matcher(text).find() } ?: return null
        return KeyRuleMatch(hit.pattern())
    }

    companion object {
        private const val MAX_PATTERNS = 20
        private const val MAX_PATTERN_LENGTH = 200
        private const val MAX_DISABLE_MINUTES = 10_080
        private val ID_PATTERN = Regex("[a-z0-9_]{1,40}")
    }
}

/** 命中的模式；规则只按状态码命中时为 null。 */
internal data class KeyRuleMatch(val matchedPattern: String?)

/** 单条规则的失败时间戳。连续与窗口计数共用，差别只在成功是否清零。 */
@Serializable
data class KeyRuleCounter(val timestamps: List<Long> = emptyList())

/** 按规则 id 保存的失败计数；规则删除或修改后对应计数会被丢弃。 */
@Serializable
data class KeyHealthState(val counters: Map<String, KeyRuleCounter> = emptyMap())

/** 达到阈值时返回的停用决定。 */
data class KeyDisableDecision(
    val ruleId: String,
    val ruleName: String,
    val matchedPattern: String?,
    val count: Int,
    val threshold: Int,
    val action: KeyDisableAction,
    val disableUntil: Long?,
) {
    init {
        require(action == KeyDisableAction.PERMANENT || disableUntil != null) { "临时禁用必须带到期时间" }
        require(action == KeyDisableAction.TEMPORARY || disableUntil == null) { "永久禁用不能带到期时间" }
    }
}

/** 一次记账的结果；[decision] 非空表示本次达到阈值，需要停用密钥。 */
data class KeyHealthUpdate(
    val state: KeyHealthState,
    val decision: KeyDisableDecision? = null,
)

/**
 * 密钥停用状态。只有 [KeyDisableKind.TEMPORARY] 会到期自动恢复，
 * 到期后的第一个请求充当探测：成功清零计数，失败重新计时。
 *
 * @author 文杰
 * @since 2026-10-29
 */
data class KeyDisableStatus(
    val kind: KeyDisableKind = KeyDisableKind.NONE,
    val until: Long? = null,
    val reason: String? = null,
) {
    init {
        require(kind == KeyDisableKind.TEMPORARY || until == null) { "只有临时停用可以带到期时间" }
        require(kind != KeyDisableKind.TEMPORARY || until != null) { "临时停用必须带到期时间" }
    }

    /** 恰好到期即视为可用，避免多等一个调度周期。 */
    fun isAvailable(now: Long): Boolean = when (kind) {
        KeyDisableKind.NONE -> true
        KeyDisableKind.TEMPORARY -> until != null && now >= until
        KeyDisableKind.MANUAL, KeyDisableKind.PERMANENT -> false
    }

    val autoRecoverable: Boolean get() = kind == KeyDisableKind.TEMPORARY
}

/**
 * 密钥健康策略：全局开关 + 有序规则列表。
 *
 * 连续计数（[KeyDisableRule.streak]）只在成功时清零；窗口计数保留窗口内的失败，
 * 因为一次成功不能证明上游已经稳定。两者都受各自窗口约束，过期时间戳会被丢弃。
 *
 * 未命中任何规则的上游响应（如 400/404）既不计数也不打断连续计数：这类错误由调用方
 * 请求内容引起，不能证明凭据是好的。只有成功才证明凭据可用。
 *
 * @author 文杰
 * @since 2026-10-29
 */
@Serializable
data class KeyDisablePolicy(
    val enabled: Boolean = true,
    val rules: List<KeyDisableRule> = DEFAULT_RULES,
) {
    init {
        require(rules.isNotEmpty()) { "至少需要一条自动禁用规则" }
        require(rules.size <= MAX_RULES) { "最多配置 $MAX_RULES 条自动禁用规则" }
        require(rules.map { it.id }.toSet().size == rules.size) { "规则 id 不能重复" }
    }

    fun record(state: KeyHealthState, signal: KeyAttemptSignal, now: Long): KeyHealthUpdate {
        if (!enabled) return KeyHealthUpdate(KeyHealthState())
        val rulesById = rules.associateBy { it.id }
        // 规则被删除或改名后，旧计数直接丢弃，避免用已下线规则的计数触发停用。
        val counters = state.counters
            .filterKeys { it in rulesById }
            .mapValuesTo(mutableMapOf()) { (_, counter) -> counter }

        if (signal == KeyAttemptSignal.Success) {
            counters.keys.removeAll { id -> rulesById.getValue(id).streak }
            return KeyHealthUpdate(prune(counters, rulesById, now))
        }
        if (signal == KeyAttemptSignal.Ignored) {
            return KeyHealthUpdate(prune(counters, rulesById, now))
        }

        val matched = rules.firstNotNullOfOrNull { rule -> rule.match(signal)?.let { rule to it } }
            ?: return KeyHealthUpdate(prune(counters, rulesById, now))
        val (rule, hit) = matched
        val cutoff = now - rule.windowMinutes * 60_000L
        val recent = counters[rule.id]?.timestamps.orEmpty().filter { it > cutoff }
        val timestamps = (recent + now).takeLast(rule.threshold)
        counters[rule.id] = KeyRuleCounter(timestamps)
        val pruned = prune(counters, rulesById, now)
        if (timestamps.size < rule.threshold) return KeyHealthUpdate(pruned)
        return KeyHealthUpdate(
            state = pruned,
            decision = KeyDisableDecision(
                ruleId = rule.id,
                ruleName = rule.name,
                matchedPattern = hit.matchedPattern,
                count = timestamps.size,
                threshold = rule.threshold,
                action = rule.action,
                disableUntil = rule.disableMinutes?.let { minutes -> now + minutes * 60_000L },
            ),
        )
    }

    private fun prune(
        counters: Map<String, KeyRuleCounter>,
        rulesById: Map<String, KeyDisableRule>,
        now: Long,
    ): KeyHealthState {
        val kept = buildMap {
            counters.forEach { (id, counter) ->
                val rule = rulesById[id] ?: return@forEach
                val cutoff = now - rule.windowMinutes * 60_000L
                val timestamps = counter.timestamps.filter { it > cutoff }
                if (timestamps.isNotEmpty()) put(id, KeyRuleCounter(timestamps))
            }
        }
        return KeyHealthState(kept)
    }

    companion object {
        const val MAX_RULES = 50

        internal val LEGACY_DEFAULT_RULES: List<KeyDisableRule> = listOf(
            KeyDisableRule(
                id = "credential_invalid",
                name = "凭据失效",
                statusCodes = listOf(401, 403),
                bodyPatterns = listOf(
                    "invalid[\\s_-]*(x-)?api[\\s_-]?key",
                    "incorrect api key",
                    "authentication_error",
                    "invalid_grant",
                ),
                threshold = 2,
                action = KeyDisableAction.PERMANENT,
            ),
            KeyDisableRule(
                id = "account_blocked",
                name = "上游账号被停",
                statusCodes = listOf(401, 403),
                bodyPatterns = listOf(
                    "account[^\\n]{0,40}(disabled|suspended|deactivated)",
                    "\\bbilling\\b",
                    "\\bpayment\\b",
                ),
                threshold = 1,
                action = KeyDisableAction.PERMANENT,
            ),
            KeyDisableRule(
                id = "auth_rejected",
                name = "鉴权被拒",
                statusCodes = listOf(401, 403),
                threshold = 3,
                streak = false,
                action = KeyDisableAction.TEMPORARY,
                disableMinutes = 30,
            ),
            KeyDisableRule(
                id = "upstream_unstable",
                name = "上游不稳定",
                statusCodes = listOf(429),
                statusCodeAtLeast = 500,
                includeTransportFailures = true,
                threshold = 8,
                streak = false,
                action = KeyDisableAction.TEMPORARY,
                disableMinutes = 10,
            ),
        )

        /** 默认规则：先处理余额不足，再确认凭据失效，最后兜底鉴权拒绝与上游不稳定。 */
        val DEFAULT_RULES: List<KeyDisableRule> = listOf(
            KeyDisableRule(
                id = "insufficient_balance",
                name = "账户余额不足",
                statusCodes = listOf(401, 402, 403),
                bodyPatterns = listOf("INSUFFICIENT_BALANCE"),
                threshold = 1,
                action = KeyDisableAction.PERMANENT,
            ),
        ) + LEGACY_DEFAULT_RULES
    }
}

/** RE2 保证线性匹配时间，避免上游错误体触发灾难性回溯。 */
private fun compilePattern(index: Int, pattern: String): Pattern = try {
    Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)
} catch (cause: PatternSyntaxException) {
    throw IllegalArgumentException("第 ${index + 1} 个匹配模式不是合法的 RE2 表达式: $pattern", cause)
}
