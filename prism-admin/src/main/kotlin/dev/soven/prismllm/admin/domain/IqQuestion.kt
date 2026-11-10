package dev.soven.prismllm.admin.domain

/** Standard answers stay in the administration domain and never form part of a model request. */
data class IqQuestion(
    val id: Long,
    val title: String,
    val prompt: String,
    val answerType: String,
    val expectedAnswer: String,
    val enabled: Boolean,
    val updatedAt: Long,
)

data class IqQuestionInput(
    val title: String,
    val prompt: String,
    val answerType: String,
    val expectedAnswer: String,
    val enabled: Boolean,
)

/** Decimal comparison without floating-point rounding; bounded exponent and precision. */
fun normalizeIqAnswer(answerType: String, value: String): String? {
    val text = value.trim()
    if (text.isEmpty() || text.length > 2000) return null
    return when (answerType) {
        "text" -> text
        "number" -> {
            if (!IQ_NUMBER.matches(text)) return null
            val number = text.toBigDecimalOrNull() ?: return null
            if (number.scale().toLong() !in -10000L..10000L || number.precision() > 2000) return null
            val normalized = number.stripTrailingZeros()
            if (normalized.scale().toLong() !in -10000L..10000L) return null
            normalized.toString().takeIf { it.length <= 2000 }
        }
        else -> null
    }
}

private val IQ_NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
