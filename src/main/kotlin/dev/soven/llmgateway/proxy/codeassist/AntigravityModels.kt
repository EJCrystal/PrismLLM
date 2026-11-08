package dev.soven.llmgateway.proxy.codeassist

/**
 * Antigravity 把一个模型按思考等级拆成多个 id（gemini-3.7-flash-low /
 * -medium / -high）。这里把它们收成一个"家族"模型，再按请求里的等级
 * 选回具体变体；规则来自 magpie 的 antigravity_models.go。
 *
 * @author 文杰
 * @since 2026-11-06
 */
object AntigravityModels {
    data class Model(
        val id: String,
        val name: String = id,
        val context: Long? = null,
        val output: Long? = null,
        val imageInput: Boolean = false,
    )

    data class Family(val id: String, val variants: List<Model>, val efforts: List<String>)

    /** 等级从低到高，用来给没有精确命中的请求取最近一级。 */
    private val levelRank = listOf("minimal", "low", "medium", "high", "xhigh")

    /** 长词在前，避免 extra-high 被 high 先截断。 */
    private val effortWords = listOf(
        "extra-high" to "xhigh",
        "extra-low" to "minimal",
        "xhigh" to "xhigh",
        "minimal" to "minimal",
        "low" to "low",
        "medium" to "medium",
        "high" to "high",
    )

    fun splitId(id: String): Pair<String, String> {
        for ((word, level) in effortWords) {
            val suffix = "-" + word
            if (id.endsWith(suffix) && id.length > suffix.length) {
                return id.dropLast(suffix.length) to level
            }
        }
        return id to ""
    }

    /** 名称末尾括号里的等级，例如 "Gemini 3.7 Flash (High)"。 */
    fun nameLevel(name: String): Pair<String, String> {
        val open = name.lastIndexOf(" (")
        if (open < 0 || !name.endsWith(")")) return name to ""
        val level = when (name.substring(open + 2, name.length - 1).lowercase()) {
            "minimal", "extra low" -> "minimal"
            "low" -> "low"
            "medium" -> "medium"
            "high" -> "high"
            "extra high", "xhigh" -> "xhigh"
            else -> return name to ""
        }
        return name.substring(0, open).trim() to level
    }

    fun families(raw: List<Model>): List<Family> {
        val order = mutableListOf<String>()
        val grouped = mutableMapOf<String, MutableList<Model>>()
        for (model in raw) {
            val family = splitId(model.id).first
            if (family !in grouped) {
                order += family
                grouped[family] = mutableListOf()
            }
            grouped.getValue(family) += model
        }
        return order.map { family ->
            val variants = grouped.getValue(family)
            // 名称里的等级都要解析得出来、且两两不同时以名称为准；否则看 id 后缀。
            val namedLevels = variants.map { nameLevel(it.name).second }
            val useNames = namedLevels.all { it.isNotEmpty() } && namedLevels.distinct().size == variants.size
            Family(
                id = family,
                variants = variants,
                efforts = variants.mapIndexed { index, model ->
                    if (useNames) namedLevels[index] else splitId(model.id).second
                },
            )
        }
    }

    /** 每个等级对应的 id；空串是没指定等级时该用的。 */
    fun byEffort(family: Family): Map<String, String> {
        val out = linkedMapOf<String, String>()
        family.variants.forEachIndexed { index, model ->
            out.putIfAbsent(family.efforts[index], model.id)
        }
        if ("" !in out) {
            out[""] = out["high"] ?: levelRank.asReversed().firstNotNullOfOrNull { out[it] } ?: family.id
        }
        return out
    }

    /** 收成家族后的模型列表：id 是家族、名称去掉等级。 */
    fun collapse(raw: List<Model>): List<Model> = families(raw).map { family ->
        // 单变体家族没有可切换的档位，发布原始 id，避免客户端拿到目录里不存在的家族 id。
        if (family.variants.size == 1) return@map family.variants.single()
        val default = byEffort(family)[""] ?: family.id
        val base = family.variants.firstOrNull { it.id == default } ?: family.variants.first()
        base.copy(
            id = family.id,
            name = nameLevel(base.name).first.ifEmpty { family.id },
        )
    }

    /** 对外发布的模型 id 对应的可调用变体；不是家族时原样返回。 */
    fun defaultVariant(model: String, raw: List<Model>): String {
        val family = families(raw).firstOrNull { it.id == model } ?: return model
        return byEffort(family)[""] ?: model
    }

    /** 把请求里的模型和等级落到具体变体 id。 */
    fun sentId(model: String, effort: String?, raw: List<Model>): String {
        val familyId = splitId(model).first
        // 不指定等级、又直接用了可识别为变体的 id：保持客户端的选择。
        if (effort.isNullOrBlank() && familyId != model) return model
        val family = families(raw).firstOrNull { it.id == familyId } ?: return model
        val variants = byEffort(family)
        if (effort.isNullOrBlank()) {
            // 请求里的 id 本身就在目录中（单变体家族发布的就是原始 id）时原样发出。
            if (model == familyId && family.variants.any { it.id == familyId }) return model
            return variants[""] ?: model
        }
        // 只在家族真实拥有的等级里取最近的一级，避免落到不存在的档位。
        val wanted = levelRank.indexOf(normalize(effort)).takeIf { it >= 0 } ?: levelRank.indexOf("high")
        val available = family.efforts.filter { it.isNotEmpty() }.distinct()
        val nearest = available.minWithOrNull(
            compareBy({ level -> kotlin.math.abs(levelRank.indexOf(level) - wanted) }, { level -> -levelRank.indexOf(level) }),
        )
        return variants[nearest ?: ""] ?: variants[""] ?: model
    }

    private fun normalize(effort: String): String = when (effort.lowercase()) {
        "none", "minimal" -> "minimal"
        "low" -> "low"
        "medium" -> "medium"
        "high" -> "high"
        "xhigh", "max" -> "xhigh"
        else -> "high"
    }
}
