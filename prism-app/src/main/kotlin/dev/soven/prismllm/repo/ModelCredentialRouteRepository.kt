package dev.soven.prismllm.repo

import dev.soven.prismllm.routing.readmodel.CredentialRouteIdentity
import dev.soven.prismllm.routing.readmodel.CredentialRouteType
import dev.soven.prismllm.routing.readmodel.ModelCredentialRouteReader
import dev.soven.prismllm.routing.readmodel.ModelCredentialRouteSnapshot
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

/** One configured ordered route per public model. */
object ModelCredentialRoutes : Table("model_credential_routes") {
    val modelId = varchar("model_id", 200)
    val revision = long("revision")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(modelId)
}

/** Identity rows intentionally have no credential foreign keys so deleted references remain inspectable. */
object ModelCredentialRouteEntries : Table("model_credential_route_entries") {
    val modelId = varchar("model_id", 200).references(ModelCredentialRoutes.modelId, onDelete = ReferenceOption.CASCADE)
    val position = integer("position")
    val channelId = varchar("channel_id", 36)
    val credentialType = varchar("credential_type", 16)
    val credentialId = varchar("credential_id", 36)

    override val primaryKey = PrimaryKey(modelId, position)

    init {
        uniqueIndex(modelId, channelId, credentialType, credentialId)
    }
}

class ModelCredentialRouteRevisionChangedException : RuntimeException("模型凭据顺序已变化，请刷新后再保存")

/**
 * Durable ordered routes live in prism-app because they join channel and OAuth credential contexts.
 * Reads expose identity and order only; no credential material is selected by this repository.
 */
class ModelCredentialRouteRepository(private val database: Database) : ModelCredentialRouteReader {
    override suspend fun find(modelId: String): ModelCredentialRouteSnapshot? = suspendTransaction(database) {
        findInTransaction(modelId)
    }

    override suspend fun list(): List<ModelCredentialRouteSnapshot> = suspendTransaction(database) {
        listInTransaction()
    }

    private fun requireTransaction() {
        check(TransactionManager.current().db === database) { "model route repository transaction database mismatch" }
    }

    /** Cross-context snapshot reads reuse the caller's transaction without opening another transaction. */
    fun listInTransaction(): List<ModelCredentialRouteSnapshot> {
        requireTransaction()
        return joinedRoutes().selectAll()
            .orderBy(ModelCredentialRoutes.modelId to SortOrder.ASC, ModelCredentialRouteEntries.position to SortOrder.ASC)
            .toList().groupBy { it[ModelCredentialRoutes.modelId] }.map { (modelId, rows) ->
                ModelCredentialRouteSnapshot(modelId, rows.first()[ModelCredentialRoutes.revision], rows.mapNotNull(::identity))
            }
    }

    /** Full replacement guarded by the caller's last observed revision. */
    suspend fun save(
        modelId: String,
        expectedRevision: Long,
        entries: List<CredentialRouteIdentity>,
        validate: (ModelCredentialRouteSnapshot) -> Unit = {},
    ): ModelCredentialRouteSnapshot = suspendTransaction(database) {
        require(modelId.length in 1..200)
        require(expectedRevision in 0 until Long.MAX_VALUE)
        require(entries.distinct().size == entries.size)
        require(entries.all { it.channelId.length in 1..36 && it.credentialId.length in 1..36 })
        val now = System.currentTimeMillis()
        val inserted = ModelCredentialRoutes.insertIgnore {
            it[ModelCredentialRoutes.modelId] = modelId
            it[revision] = 0
            it[updatedAt] = now
        }.insertedCount > 0
        val current = ModelCredentialRoutes.selectAll()
            .where { ModelCredentialRoutes.modelId eq modelId }
            .forUpdate()
            .single()
        val revision = current[ModelCredentialRoutes.revision]
        if (revision != expectedRevision) throw ModelCredentialRouteRevisionChangedException()

        val currentSnapshot = ModelCredentialRouteSnapshot(modelId, revision, entries(modelId), configured = !inserted)
        validate(currentSnapshot)
        ModelCredentialRouteEntries.deleteWhere { ModelCredentialRouteEntries.modelId eq modelId }
        entries.forEachIndexed { position, entry ->
            ModelCredentialRouteEntries.insert {
                it[ModelCredentialRouteEntries.modelId] = modelId
                it[ModelCredentialRouteEntries.position] = position
                it[channelId] = entry.channelId
                it[credentialType] = entry.credentialType.value
                it[credentialId] = entry.credentialId
            }
        }
        ModelCredentialRoutes.update({ ModelCredentialRoutes.modelId eq modelId }) {
            it[ModelCredentialRoutes.revision] = revision + 1
            it[updatedAt] = now
        }
        ModelCredentialRouteSnapshot(modelId, revision + 1, entries.toList())
    }

    fun findInTransaction(modelId: String): ModelCredentialRouteSnapshot? {
        requireTransaction()
        val rows = joinedRoutes().selectAll()
            .where { ModelCredentialRoutes.modelId eq modelId }
            .orderBy(ModelCredentialRouteEntries.position to SortOrder.ASC).toList()
        if (rows.isEmpty()) return null
        return ModelCredentialRouteSnapshot(modelId, rows.first()[ModelCredentialRoutes.revision], rows.mapNotNull(::identity))
    }

    private fun joinedRoutes() = ModelCredentialRoutes.join(
        ModelCredentialRouteEntries, JoinType.LEFT,
        ModelCredentialRoutes.modelId, ModelCredentialRouteEntries.modelId,
    )

    private fun identity(row: ResultRow): CredentialRouteIdentity? {
        val credentialId = row.getOrNull(ModelCredentialRouteEntries.credentialId) ?: return null
        return CredentialRouteIdentity(
            channelId = row[ModelCredentialRouteEntries.channelId],
            credentialType = requireNotNull(CredentialRouteType.fromValue(row[ModelCredentialRouteEntries.credentialType])),
            credentialId = credentialId,
        )
    }

    private fun entries(modelId: String): List<CredentialRouteIdentity> =
        ModelCredentialRouteEntries.selectAll()
            .where { ModelCredentialRouteEntries.modelId eq modelId }
            .orderBy(ModelCredentialRouteEntries.position to SortOrder.ASC)
            .map { row ->
                CredentialRouteIdentity(
                    channelId = row[ModelCredentialRouteEntries.channelId],
                    credentialType = requireNotNull(CredentialRouteType.fromValue(row[ModelCredentialRouteEntries.credentialType])),
                    credentialId = row[ModelCredentialRouteEntries.credentialId],
                )
            }
}
