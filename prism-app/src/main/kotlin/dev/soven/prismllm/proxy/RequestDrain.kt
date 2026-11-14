package dev.soven.prismllm.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull

/** 准入与停机共用同一锁；正常 Drain 同时等待请求终结和下游末帧写入。 */
internal class RequestDrain {
    private val lock = Any()
    private val active = mutableSetOf<Lease>()
    private val drained = CompletableDeferred<Unit>()
    private val finalized = CompletableDeferred<Unit>()
    private var stopping = false

    val shuttingDown: Boolean
        get() = synchronized(lock) { stopping }

    fun acquire(job: Job): Lease? = synchronized(lock) {
        if (stopping) null else Lease(job).also { active += it }
    }

    fun beginShutdown() = synchronized(lock) {
        stopping = true
        completeIfReady()
    }

    suspend fun awaitDrained(timeoutMs: Long): Boolean = await(drained, timeoutMs)

    suspend fun awaitFinalized(timeoutMs: Long): Boolean = await(finalized, timeoutMs)

    fun cancelRemaining() {
        val jobs = synchronized(lock) { active.filterNot { it.finalized }.map { it.job }.distinct() }
        jobs.forEach { it.cancel(CancellationException("gateway shutdown drain timeout")) }
    }

    private suspend fun await(completion: CompletableDeferred<Unit>, timeoutMs: Long): Boolean {
        if (completion.isCompleted) return true
        return withTimeoutOrNull(timeoutMs) { completion.await(); true } ?: false
    }

    private fun completeIfReady() {
        if (!stopping) return
        if (active.isEmpty()) drained.complete(Unit)
        if (active.none { !it.finalized }) finalized.complete(Unit)
    }

    inner class Lease internal constructor(internal val job: Job) : AutoCloseable {
        internal var finalized = false
            private set
        private var responseCompleted = true

        internal fun awaitResponse() = synchronized(lock) {
            check(!finalized)
            responseCompleted = false
        }

        internal fun responseCompleted() = synchronized(lock) {
            responseCompleted = true
            if (finalized) active.remove(this)
            completeIfReady()
        }

        override fun close() = synchronized(lock) {
            finalized = true
            if (responseCompleted) active.remove(this)
            completeIfReady()
        }
    }
}
