package dev.soven.prismllm.api

import dev.soven.prismllm.admin.domain.IqQuestion
import dev.soven.prismllm.admin.domain.IqQuestionInput
import dev.soven.prismllm.admin.domain.normalizeIqAnswer
import dev.soven.prismllm.shared.error.badRequest
import kotlinx.serialization.Serializable

@Serializable
data class IqQuestionRequest(
    val title: String,
    val prompt: String,
    val answerType: String,
    val expectedAnswer: String,
    val enabled: Boolean = true,
) {
    fun validated(): IqQuestionInput {
        val title = title.trim()
        val prompt = prompt.trim()
        if (title.length !in 1..120) throw badRequest("invalid_iq_question", "题目标题长度必须在 1..120 之间")
        if (prompt.length !in 1..20000) throw badRequest("invalid_iq_question", "题干长度必须在 1..20000 之间")
        val answer = normalizeIqAnswer(answerType, expectedAnswer)
            ?: throw badRequest("invalid_iq_question", "答案必须是有效数字或非空文本，最长 2000 字符；数字指数绝对值不能超过 10000")
        return IqQuestionInput(title, prompt, answerType, answer, enabled)
    }
}

@Serializable
data class IqQuestionResponse(
    val id: Long,
    val title: String,
    val prompt: String,
    val answerType: String,
    val expectedAnswer: String,
    val enabled: Boolean,
    val updatedAt: Long,
)

fun IqQuestion.toResponse() = IqQuestionResponse(id, title, prompt, answerType, expectedAnswer, enabled, updatedAt)
