package dev.soven.prismllm.repo

import dev.soven.prismllm.routing.readmodel.CredentialRouteIdentity
import dev.soven.prismllm.routing.readmodel.CredentialRouteType
import dev.soven.prismllm.routing.readmodel.ModelCredentialRouteSnapshot
import dev.soven.prismllm.testsupport.db.SharedPostgres
import dev.soven.prismllm.testsupport.db.TestDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@Tag("component")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelCredentialRouteRepositoryTest {
    private lateinit var database: TestDatabase
    private lateinit var repository: ModelCredentialRouteRepository

    @BeforeAll
    fun start() {
        val postgres = SharedPostgres.container
        database = TestDatabase(SharedPostgres.newDatabase(), postgres.username, postgres.password)
        repository = ModelCredentialRouteRepository(database.database)
    }

    @AfterAll
    fun stop() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun `save is ordered revision guarded and preserves explicit empty route`() = runBlocking {
        val model = "repo/${java.util.UUID.randomUUID()}"
        assertNull(repository.find(model))
        val first = CredentialRouteIdentity("channel-a", CredentialRouteType.STATIC, "credential")
        val second = CredentialRouteIdentity("channel-a", CredentialRouteType.OAUTH, "credential")
        val saved = repository.save(model, 0, listOf(first, second))
        assertEquals(ModelCredentialRouteSnapshot(model, 1, listOf(first, second)), saved)
        assertEquals(saved, repository.find(model))
        assertFailsWith<ModelCredentialRouteRevisionChangedException> {
            repository.save(model, 0, emptyList())
        }
        val cleared = repository.save(model, 1, emptyList())
        assertEquals(2L, cleared.revision)
        assertEquals(emptyList(), cleared.entries)
        assertEquals(cleared, repository.list().single { it.modelId == model })
    }

    @Test
    fun `validation failure rolls back initial route row`() = runBlocking {
        val model = "invalid/${java.util.UUID.randomUUID()}"
        assertFailsWith<IllegalStateException> {
            repository.save(model, 0, emptyList()) { error("validation rejected") }
        }
        assertNull(repository.find(model))
    }

    @Test
    fun `saved order survives a new repository instance`() = runBlocking {
        val model = "durable/${java.util.UUID.randomUUID()}"
        val entries = listOf(
            CredentialRouteIdentity("channel-b", CredentialRouteType.OAUTH, "account"),
            CredentialRouteIdentity("channel-a", CredentialRouteType.STATIC, "key"),
        )
        val saved = repository.save(model, 0, entries)
        val reopened = ModelCredentialRouteRepository(database.database)
        assertEquals(saved, reopened.find(model))
        val reordered = reopened.save(model, saved.revision, entries.reversed())
        assertEquals(reordered, repository.find(model))
    }

    @Test
    fun `concurrent initial saves allow only one writer for revision zero`() = runBlocking {
        val model = "concurrent/${java.util.UUID.randomUUID()}"
        val start = CompletableDeferred<Unit>()
        val results = coroutineScope {
            val writers = listOf("first", "second").map { id ->
                async(Dispatchers.IO) {
                    start.await()
                    runCatching {
                        ModelCredentialRouteRepository(database.database).save(
                            model, 0,
                            listOf(CredentialRouteIdentity("channel", CredentialRouteType.STATIC, id)),
                        )
                    }
                }
            }
            start.complete(Unit)
            writers.awaitAll()
        }
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.exceptionOrNull() is ModelCredentialRouteRevisionChangedException })
        assertEquals(results.single { it.isSuccess }.getOrThrow(), repository.find(model))
    }
}
