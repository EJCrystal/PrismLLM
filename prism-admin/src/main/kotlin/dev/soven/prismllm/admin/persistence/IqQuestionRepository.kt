package dev.soven.prismllm.admin.persistence

import dev.soven.prismllm.admin.domain.IqQuestion
import dev.soven.prismllm.admin.domain.IqQuestionInput
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

object IqQuestions : Table("iq_questions") {
    val id = long("id").autoIncrement()
    val title = varchar("title", 120)
    val prompt = text("prompt")
    val answerType = varchar("answer_type", 10)
    val expectedAnswer = text("expected_answer")
    val enabled = bool("enabled")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

class IqQuestionRepository(private val database: Database) {
    suspend fun list(limit: Int = 200, offset: Long = 0): List<IqQuestion> = suspendTransaction(database) {
        IqQuestions.selectAll().orderBy(IqQuestions.id, SortOrder.DESC).limit(limit.coerceIn(1, 200)).offset(offset.coerceAtLeast(0)).map(::toRecord)
    }

    suspend fun enabled(): List<IqQuestion> = suspendTransaction(database) {
        IqQuestions.selectAll().where { IqQuestions.enabled eq true }.map(::toRecord)
    }

    suspend fun create(input: IqQuestionInput): IqQuestion = suspendTransaction(database) {
        IqQuestions.insertReturning {
            it[title] = input.title
            it[prompt] = input.prompt
            it[answerType] = input.answerType
            it[expectedAnswer] = input.expectedAnswer
            it[enabled] = input.enabled
            it[updatedAt] = System.currentTimeMillis()
        }.single().let(::toRecord)
    }

    suspend fun update(id: Long, input: IqQuestionInput): IqQuestion? = suspendTransaction(database) {
        val count = IqQuestions.update({ IqQuestions.id eq id }) {
            it[title] = input.title
            it[prompt] = input.prompt
            it[answerType] = input.answerType
            it[expectedAnswer] = input.expectedAnswer
            it[enabled] = input.enabled
            it[updatedAt] = System.currentTimeMillis()
        }
        if (count == 0) null else IqQuestions.selectAll().where { IqQuestions.id eq id }.single().let(::toRecord)
    }

    suspend fun delete(id: Long): Boolean = suspendTransaction(database) {
        IqQuestions.deleteWhere { IqQuestions.id eq id } > 0
    }

    private fun toRecord(row: ResultRow) = IqQuestion(
        id = row[IqQuestions.id], title = row[IqQuestions.title], prompt = row[IqQuestions.prompt],
        answerType = row[IqQuestions.answerType], expectedAnswer = row[IqQuestions.expectedAnswer],
        enabled = row[IqQuestions.enabled], updatedAt = row[IqQuestions.updatedAt],
    )
}
