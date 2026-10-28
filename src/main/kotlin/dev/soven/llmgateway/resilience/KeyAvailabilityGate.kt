package dev.soven.llmgateway.resilience

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class KeyAvailabilityGate {
    private class State {
        var readers: Int = 0
        var writerActive: Boolean = false
        var waitingWriters: Int = 0
        var changed = CompletableDeferred<Unit>()
    }

    private val mutex = Mutex()
    private val states = ConcurrentHashMap<String, State>()

    suspend fun <T> withReader(keyId: String, block: suspend () -> T): T {
        val state = stateFor(keyId)
        while (true) {
            val waitFor = mutex.withLock {
                if (!state.writerActive && state.waitingWriters == 0) {
                    state.readers += 1
                    null
                } else {
                    state.changed
                }
            }
            if (waitFor == null) break
            waitFor.await()
        }
        try {
            return block()
        } finally {
            mutex.withLock {
                state.readers -= 1
                if (state.readers == 0) signal(state)
            }
        }
    }

    suspend fun <T> withWriter(keyId: String, block: suspend () -> T): T {
        val state = stateFor(keyId)
        mutex.withLock { state.waitingWriters += 1 }
        var acquired = false
        try {
            while (true) {
                val waitFor = mutex.withLock {
                    if (!state.writerActive && state.readers == 0) {
                        state.waitingWriters -= 1
                        state.writerActive = true
                        acquired = true
                        null
                    } else {
                        state.changed
                    }
                }
                if (waitFor == null) break
                waitFor.await()
            }
            return block()
        } finally {
            mutex.withLock {
                if (acquired) {
                    state.writerActive = false
                    signal(state)
                } else {
                    state.waitingWriters -= 1
                    signal(state)
                }
            }
        }
    }

    private fun stateFor(keyId: String): State = states.computeIfAbsent(keyId) { State() }

    private fun signal(state: State) {
        val previous = state.changed
        state.changed = CompletableDeferred()
        previous.complete(Unit)
    }
}
