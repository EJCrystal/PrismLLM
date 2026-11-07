package dev.soven.llmgateway.resilience

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

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

    /**
     * 是否参与路由。临时停用到期即视为可用，不依赖后台清理任务；
     * 人工停用与永久停用永不自动放行。
     *
     * 持久化层与健康概览都用这一份规则，避免两处判定漂移。
     */
    fun isRoutableAt(autoDisableUntil: Long?, probeUntil: Long?, now: Long): Boolean = when (this) {
        ACTIVE -> true
        AUTO_TEMPORARY -> autoDisableUntil != null && now >= autoDisableUntil &&
            (probeUntil == null || now >= probeUntil)
        MANUAL_DISABLED, AUTO_PERMANENT -> false
    }

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

/** 渠道自动禁用策略的三种形态，对应管理台的三选一。 */
enum class ChannelKeyDisableMode(val value: String) {
    /** 未配置覆盖：完全使用全局设置。 */
    INHERIT("inherit"),

    /** 渠道自带规则在前，未命中时回落到全局规则。 */
    CUSTOM("custom"),

    /** 本渠道不执行任何自动禁用，渠道规则和全局规则都不生效。 */
    DISABLED("disabled"),
    ;

    companion object {
        fun of(override: KeyDisablePolicy?): ChannelKeyDisableMode = when {
            override == null -> INHERIT
            override.enabled -> CUSTOM
            else -> DISABLED
        }
    }
}

/**
 * 渠道覆盖与全局设置的组合方式：
 *
 * - 没有覆盖时完全继承全局；
 * - 覆盖关闭自动禁用时，本渠道的渠道规则和全局规则都不执行；
 * - 覆盖启用时，渠道规则排在前面、全局规则排在后面，仍是「首条命中即止」：
 *   渠道规则命中就只按它计数，即使尚未达到阈值也不会再落到全局规则，否则一次失败会被两套规则各记一次。
 *
 * 与渠道规则同 id 的全局规则不再参与兜底，等价于「渠道版本覆盖全局版本」：管理员可以在渠道里
 * 收紧某条全局规则的阈值而不必另起名字，V57 搬迁过来的整份规则副本也照常可用。
 * 代价是渠道里一条更宽的规则可能遮蔽全局里更精确的规则，这一点需要在管理台提示。
 * 全局自动禁用已关闭时不再兜底它的规则，否则「关掉全局」对渠道仍然生效，与直觉相反。
 *
 * 合并结果最多 [MAX_EFFECTIVE_RULES] 条：两份策略各自合法时，加在一起也不该让解析失败。
 * 单份策略仍受 [MAX_RULES] 限制，由保存入口校验，避免有人把两倍上限当成单份上限。
 * 万一库里已有超出单份上限的历史数据，合并按渠道规则优先截断，宁可少兜底也不让解析抛异常——
 * 解析失败会让该渠道的记账与管理接口一起 500，比丢几条兜底规则严重得多。
 */
fun KeyDisablePolicy.fallbackTo(global: KeyDisablePolicy): KeyDisablePolicy {
    if (!enabled || !global.enabled) return this
    val ownIds = rules.mapTo(HashSet(rules.size)) { it.id }
    val merged = rules + global.rules.filterNot { it.id in ownIds }
    return KeyDisablePolicy(
        enabled = true,
        rules = if (merged.size <= KeyDisablePolicy.MAX_EFFECTIVE_RULES) {
            merged
        } else {
            merged.take(KeyDisablePolicy.MAX_EFFECTIVE_RULES)
        },
    )
}

/** 渠道覆盖的大小上限；合并进全局兜底之前，单份策略不能超过 [KeyDisablePolicy.MAX_RULES]。 */
fun KeyDisablePolicy.requireSavable(): KeyDisablePolicy {
    require(rules.size <= KeyDisablePolicy.MAX_RULES) { "最多配置 ${KeyDisablePolicy.MAX_RULES} 条自动禁用规则" }
    return this
}

/**
 * 一次记账实际生效的策略。[policy] 已经是合并后的结果：渠道覆盖只提供自己的规则，
 * 全局规则作为未命中时的兜底。
 *
 * 计数归属使用 [fingerprint] 而不是 [revision]：渠道覆盖叠加全局兜底后，有效策略可能因为
 * 全局设置变化而改变，单看渠道版本号会漏掉这种情况，导致用旧规则累计的失败去触发新规则。
 * 指纹覆盖作用域与完整规则内容，任一构成变化都会让旧计数失效。
 *
 * @author 文杰
 * @since 2026-11-07
 */
data class ResolvedKeyDisablePolicy(
    val scope: String,
    val revision: Long,
    val policy: KeyDisablePolicy,
) {
    /** 有效策略的稳定摘要；只有内容完全一致时才能继续沿用已累计的失败计数。 */
    val fingerprint: Long by lazy { policyFingerprint(scope, policy) }

    companion object {
        /** 全局设置对应的计数作用域。 */
        const val GLOBAL_SCOPE: String = "global"

        fun channelScope(channelId: String): String = "channel:$channelId".take(80)
    }
}

/**
 * 计算计数归属指纹。SHA-256 截断到 63 位，避免落到负数；随机化哈希（如 String.hashCode 之外的
 * 实现）不可用，这个值直接写库，必须跨进程稳定。
 */
private fun policyFingerprint(scope: String, policy: KeyDisablePolicy): Long {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$scope\u0000${keyDisableFingerprintJson.encodeToString(policy)}".toByteArray(Charsets.UTF_8))
    var value = 0L
    for (index in 0 until 8) value = (value shl 8) or (digest[index].toLong() and 0xFF)
    return value and Long.MAX_VALUE
}

private val keyDisableFingerprintJson = Json { encodeDefaults = true }

/** 单条规则的失败时间戳。连续与窗口计数共用，差别只在成功是否清零。 */
@Serializable
data class KeyRuleCounter(val timestamps: List<Long> = emptyList())

/** 按规则 id 保存的失败计数；规则删除或修改后对应计数会被丢弃。 */
@Serializable
data class KeyHealthState(val counters: Map<String, KeyRuleCounter> = emptyMap())

/** 渠道覆盖策略的持久化编码；渠道列表与自动禁用仓库共用同一份规则格式。 */
object ChannelKeyDisablePolicyCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(policy: KeyDisablePolicy): String = json.encodeToString(policy)

    /** 空串或无法解析的内容按「未配置覆盖」处理，坏数据不能让渠道静默停用密钥。 */
    fun decode(value: String?): KeyDisablePolicy? = value
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { json.decodeFromString<KeyDisablePolicy>(it) }.getOrNull() }
}

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
        // 关闭自动禁用时不需要规则：渠道可以只表达「本渠道不执行自动禁用」，不必留一条无意义的占位规则。
        require(rules.isNotEmpty() || !enabled) { "启用自动禁用时至少需要一条规则" }
        // 构造同时服务于「保存的单份策略」和「渠道覆盖 + 全局兜底合并后的有效策略」，
        // 因此这里放的是合并上限；单份策略的上限在保存入口用 requireSavable() 校验。
        require(rules.size <= MAX_EFFECTIVE_RULES) { "最多 $MAX_EFFECTIVE_RULES 条生效规则，请减少规则数量" }
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
        /** 单份策略（全局设置或一条渠道覆盖）的规则上限。 */
        const val MAX_RULES = 50

        /** 合并后的有效策略上限：渠道规则 + 未命中的全局兜底，最多是单份上限的两倍。 */
        const val MAX_EFFECTIVE_RULES = MAX_RULES * 2

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
