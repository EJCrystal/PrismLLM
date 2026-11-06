package dev.soven.llmgateway.proxy

/**
 * 识别上游“只拒绝请求形状”的错误，参考 magpie 的 shapeRefused 处理。
 *
 * 背景：
 * 同一个请求在不同上游的接受度并不相同。某个上游不认识的消息角色、字段、参数或
 * JSON Schema 子集，另一个上游可能原样接受。这类错误说明的是“这个上游读不懂请求”，
 * 而不是“请求本身有错”，因此换一个候选渠道就可能成功，也不应让当前渠道进入冷却。
 *
 * 处理规则：
 * - 422 在 HTTP 语义上就是请求语义无法处理，一律视为形状拒绝；
 * - 400 仅在错误文本命中 [WORDS] 时才视为形状拒绝，避免把常规参数错误当成可重试错误；
 *   其中 `upstream request failed` 是 relay 自己报告“后面的上游拒绝了请求”，换候选渠道可能成功；
 * - 其他状态（含 5xx）即使包含相同文本也不参与判定。
 *
 * 词表来自 magpie 的 shapeWords，覆盖 xAI 的 "Failed to deserialize … unknown variant"、
 * OpenAI 的 "Unknown parameter" 以及 JSON Schema 嵌套深度等常见拒绝话术；
 * "field required" 这类所有上游都会拒绝的缺失字段不在词表内。
 *
 * @author 文杰
 * @since 2026-11-06
 */
object ShapeRefusal {
    private val WORDS = Regex(
        """failed to deserialize|unknown (item |content |input )?(type|variant|field|parameter)|unknown_parameter|""" +
            """unrecognized (request argument|field|parameter)|extra (inputs|fields) are not permitted|""" +
            """additional properties are not allowed|schema exceeds the maximum (nesting )?depth|""" +
            """schema[^"]{0,60}\bnesting depth\b|function_declarations\[\d+\]\.parameters\b|""" +
            """schema specified other fields alongside any_?of|upstream request failed""",
        RegexOption.IGNORE_CASE,
    )

    /** 余额或额度耗尽不是请求形状问题，不能因为同一段错误文本而触发换渠道。 */
    private val QUOTA_WORDS = Regex(
        """insufficient_quota|insufficient_balance|quota|balance|credit|billing|余额|额度|欠费|套餐""",
        RegexOption.IGNORE_CASE,
    )

    fun matches(status: Int, body: String): Boolean = when (status) {
        422 -> true
        400 -> WORDS.containsMatchIn(body) && !QUOTA_WORDS.containsMatchIn(body)
        else -> false
    }
}
