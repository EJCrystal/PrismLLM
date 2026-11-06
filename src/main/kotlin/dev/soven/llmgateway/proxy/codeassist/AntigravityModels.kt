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
            // 名称都带等级、且两两不同时以名称为准；否则看 id 后缀。
            val named = variants.mapNotNull { (_, level) -> level.takeIf { it.isNotEmpty() } }
            val useNames = named.size == variants.size && named.toSet().size == variants.size
            Family(
                id = family,
                variants = variants,
                efforts = variants.map { model ->
                    if (useNames) nameLevel(model.name).second else splitId(model.id).second
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
        val default = byEffort(family)[""] ?: family.id
        val base = family.variants.firstOrNull { it.id == default } ?: family.variants.first()
        base.copy(
            id = family.id,
            name = nameLevel(base.name).first.ifEmpty { family.id },
        )
    }

    /** 把请求里的模型和等级落到具体变体 id。 */
    fun sentId(model: String, effort: String?, raw: List<Model>): String {
        val familyId = splitId(model).first
        val family = families(raw).firstOrNull { it.id == familyId && it.variants.size > 1 } ?: return model
        val variants = byEffort(family)
        if (effort.isNullOrBlank()) {
            return if (model != familyId) model else variants[""] ?: model
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
