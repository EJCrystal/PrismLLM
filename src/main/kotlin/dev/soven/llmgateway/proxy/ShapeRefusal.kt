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
 * 词表来自 magpie 的 shapeWords 与 thinking_back.go，覆盖 xAI 的 "Failed to deserialize … unknown variant"、
 * OpenAI 的 "Unknown parameter"、JSON Schema 嵌套深度等常见拒绝话术，以及两类"这个上游读不懂本请求"的错误：
 *
 * - 思考内容未回传。DeepSeek 等要求历史 assistant 消息带回 reasoning_content，缺失时报
 *   "The `reasoning_content` in the thinking mode must be passed back to the API"；
 *   换一个不需要该字段的候选渠道就能服务，因此同样属于形状不兼容。
 * - developer 角色不被接受。部分 OpenAI 兼容上游只认 system/assistant/user/tool。
 *
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
            """schema specified other fields alongside any_?of|upstream request failed|""" +
            // 思考内容未回传：DeepSeek 的 "The reasoning_content in the thinking mode must be passed back"，
            // 以及各种把 reasoning_content 说成缺失/必填的转述（Beam 的 "Missing required parameter"）。
            """`?(?:content\[\]\.thinking|reasoning_content)`? in the thinking mode must be passed back|""" +
            """\breasoning_content\b[^"]{0,40}\b(?:is (?:missing|required)|must be (?:passed|provided|included))|""" +
            """\b(?:missing|required)\b[^"]{0,80}\breasoning_content\b|""" +
            // developer 角色不被接受：只认 system/assistant/user/tool 的上游会这样报。
            """\bdeveloper\b[^"]{0,60}\bis not one of\b""",
        RegexOption.IGNORE_CASE,
    )

    /** 余额或额度耗尽不是请求形状问题，不能因为同一段错误文本而触发换渠道。 */
    private val QUOTA_WORDS = Regex(
        """\binsufficient_quota\b|\binsufficient_balance\b|\bquota\s+(?:exceeded|exhausted|limit reached)\b|""" +
            """\bquota_(?:exceeded|exhausted)\b|\bcredit[\s_-]?balance(?:_exhausted)?\b|""" +
            """\bout of (?:quota|credit)\b|\bbilling\b|余额不足|余额不够|额度不足|额度用尽|欠费|请充值""",
        RegexOption.IGNORE_CASE,
    )

    fun matches(status: Int, body: String): Boolean = when (status) {
        422 -> true
        400 -> WORDS.containsMatchIn(body) && !QUOTA_WORDS.containsMatchIn(body)
        else -> false
    }
}
