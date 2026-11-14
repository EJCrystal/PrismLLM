package dev.soven.prismllm.proxy

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.LastHttpContent
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.ClosedChannelException
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 本机真实 Netty 输出管道；屏障持有末帧和原始 promise，而非 handler/Job 完成事件。 */
@Timeout(20)
@Tag("component")
class NettyResponseDrainTest {
    @ParameterizedTest(name = "stream={0}")
    @ValueSource(booleans = [false, true])
    fun `finalized handler cannot drain before its terminal frame is written`(stream: Boolean) = runBlocking {
        val drain = RequestDrain()
        val finalized = CompletableDeferred<Unit>()
        val gate = TerminalGate()
        withNetty(gate, {
            get("/proxy") { call.respondTracked(drain, finalized, stream) }
        }) { socket ->
            socket.send(request("/proxy"))
            val receiving = async(Dispatchers.IO) { readResponse(socket.getInputStream()) }
            val terminal = gate.terminal.bounded()
            finalized.bounded()
            gate.barrier()
            if (stream) {
                assertIs<LastHttpContent>(terminal.message)
                assertFalse(terminal.message is FullHttpResponse)
            } else {
                assertIs<FullHttpResponse>(terminal.message)
            }
            assertFalse(terminal.promise.isVoid)
            assertFalse(terminal.promise.isDone)
            drain.beginShutdown()
            assertTrue(drain.awaitFinalized(0))
            assertFalse(drain.awaitDrained(PROBE_MS))
            assertFalse(receiving.isCompleted)

            gate.release()
            assertTrue(drain.awaitDrained(WAIT_MS))
            gate.barrier()
            assertTrue(terminal.promise.isSuccess)
            val response = withTimeout(WAIT_MS) { receiving.await() }
            assertEquals(200, response.status)
            assertEquals(body(stream), response.body)
            if (stream) {
                assertEquals("chunked", response.headers["transfer-encoding"])
            } else {
                assertEquals(body(false).toByteArray().size.toString(), response.headers["content-length"])
            }
        }
    }

    @Test
    fun `closed connection drains a finalized stream even while its terminal promise is pending`() = runBlocking {
        val drain = RequestDrain()
        val finalized = CompletableDeferred<Unit>()
        val gate = TerminalGate()
        withNetty(gate, {
            get("/proxy") { call.respondTracked(drain, finalized, stream = true) }
        }) { socket ->
            socket.send(request("/proxy"))
            val terminal = gate.terminal.bounded()
            finalized.bounded()
            drain.beginShutdown()
            assertTrue(drain.awaitFinalized(0))
            assertFalse(drain.awaitDrained(PROBE_MS))

            socket.close()
            gate.inactive.bounded()
            gate.barrier()
            // Gate 尚未交出末帧，关闭不能替它完成 promise；Drain 必须观察真实 channelInactive。
            assertFalse(terminal.promise.isDone)
            assertTrue(drain.awaitDrained(WAIT_MS))
        }
    }

    @Test
    fun `shutdown timeout waits for finalization but does not wait for held output`() = runBlocking {
        val drain = RequestDrain()
        val responded = CompletableDeferred<Job>()
        val finalizing = CompletableDeferred<Unit>()
        val finishFinalization = CompletableDeferred<Unit>()
        val finalized = CompletableDeferred<Unit>()
        val gate = TerminalGate()
        withNetty(gate, {
            get("/proxy") {
                val job = assertNotNull(currentCoroutineContext()[Job])
                val lease = assertNotNull(drain.acquire(job))
                try {
                    lease.use {
                        lease.trackResponse(call)
                        call.respondBytes(body(false).toByteArray(), ContentType.Application.Json)
                        try {
                            responded.complete(job)
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                finalizing.complete(Unit)
                                withTimeout(WAIT_MS) { finishFinalization.await() }
                            }
                        }
                    }
                } finally {
                    finalized.complete(Unit)
                }
            }
        }) { socket ->
            try {
                socket.send(request("/proxy"))
                val receiving = async(Dispatchers.IO) { readResponse(socket.getInputStream()) }
                val terminal = gate.terminal.bounded()
                val job = responded.bounded()
                drain.beginShutdown()
                assertFalse(drain.awaitDrained(PROBE_MS))
                drain.cancelRemaining()
                finalizing.bounded()
                assertTrue(job.isCancelled)
                assertFalse(drain.awaitFinalized(PROBE_MS))
                assertFalse(terminal.promise.isDone)

                finishFinalization.complete(Unit)
                finalized.bounded()
                assertTrue(drain.awaitFinalized(0))
                assertFalse(drain.awaitDrained(PROBE_MS))
                assertFalse(terminal.promise.isDone)
                assertFalse(receiving.isCompleted)
                gate.release()
                assertTrue(drain.awaitDrained(WAIT_MS))
                assertEquals(body(false), withTimeout(WAIT_MS) { receiving.await() }.body)
            } finally {
                finishFinalization.complete(Unit)
            }
        }
    }

    @Test
    fun `earlier non proxy response on a pipelined connection cannot complete the proxy lease`() = runBlocking {
        val drain = RequestDrain()
        val allowOrdinary = CompletableDeferred<Unit>()
        val registered = CompletableDeferred<Unit>()
        val finalized = CompletableDeferred<Unit>()
        val ordinaryReceived = CompletableDeferred<WireResponse>()
        val gate = TerminalGate(targetResponse = 2)
        withNetty(gate, {
            get("/ordinary") {
                withTimeout(WAIT_MS) { allowOrdinary.await() }
                call.respondBytes("ordinary".toByteArray(), ContentType.Text.Plain)
            }
            get("/proxy") {
                val lease = assertNotNull(drain.acquire(assertNotNull(currentCoroutineContext()[Job])))
                lease.use {
                    lease.trackResponse(call)
                    registered.complete(Unit)
                    call.respondBytes(body(false).toByteArray(), ContentType.Application.Json)
                }
                finalized.complete(Unit)
            }
        }) { socket ->
            try {
                socket.send(request("/ordinary") + request("/proxy"))
                val receiving = async(Dispatchers.IO) {
                    val input = socket.getInputStream()
                    ordinaryReceived.complete(readResponse(input))
                    readResponse(input)
                }
                registered.bounded()
                finalized.bounded()
                drain.beginShutdown()
                assertTrue(drain.awaitFinalized(0))
                assertFalse(drain.awaitDrained(0))
                // Tracker 已安装且 proxy 已终结，才允许排在它前面的非 proxy 响应写出。
                allowOrdinary.complete(Unit)
                gate.forwardedTerminal.bounded()
                val terminal = gate.terminal.bounded()
                gate.barrier()
                val ordinary = ordinaryReceived.bounded()
                assertEquals(200, ordinary.status)
                assertEquals("ordinary", ordinary.body)
                assertFalse(terminal.promise.isDone)
                assertFalse(drain.awaitDrained(PROBE_MS))
                assertFalse(receiving.isCompleted)

                gate.release()
                assertTrue(drain.awaitDrained(WAIT_MS))
                val response = withTimeout(WAIT_MS) { receiving.await() }
                assertEquals(200, response.status)
                assertEquals(body(false), response.body)
            } finally {
                allowOrdinary.complete(Unit)
            }
        }
    }

    private suspend fun ApplicationCall.respondTracked(
        drain: RequestDrain,
        finalized: CompletableDeferred<Unit>,
        stream: Boolean,
    ) {
        val lease = assertNotNull(drain.acquire(assertNotNull(currentCoroutineContext()[Job])))
        lease.use {
            lease.trackResponse(this)
            if (stream) {
                respondBytesWriter(ContentType.Text.EventStream) {
                    writeFully(EVENT.toByteArray())
                    flush()
                    writeFully(DONE.toByteArray())
                }
            } else {
                respondBytes(body(false).toByteArray(), ContentType.Application.Json)
            }
        }
        finalized.complete(Unit)
    }

    private suspend fun withNetty(
        gate: TerminalGate,
        routes: Route.() -> Unit,
        test: suspend CoroutineScope.(Socket) -> Unit,
    ) = coroutineScope {
        val server = embeddedServer(Netty, configure = {
            connector {
                port = 0
                host = "127.0.0.1"
            }
            connectionGroupSize = 1
            workerGroupSize = 1
            callGroupSize = 2
            enableHttp2 = false
            responseWriteTimeoutSeconds = 0
            channelPipelineConfig = {
                // Outbound 逆序：生产 tracker（http1 前）先看原始帧，gate 再拦截，codec 最后编码。
                addAfter("codec", "test-terminal-gate", gate)
            }
        }) { routing(routes) }
        var client: Socket? = null
        try {
            server.start(wait = false)
            val port = server.engine.resolvedConnectors().single().port
            val socket = Socket()
            client = socket
            socket.soTimeout = WAIT_MS.toInt()
            withContext(Dispatchers.IO) { socket.connect(InetSocketAddress("127.0.0.1", port), WAIT_MS.toInt()) }
            test(socket)
        } finally {
            withContext(NonCancellable) {
                try {
                    client?.close()
                    gate.dispose()
                } finally {
                    withContext(Dispatchers.IO) { server.stop(0, 1_000) }
                }
            }
        }
    }

    private class TerminalGate(private val targetResponse: Int = 1) : ChannelDuplexHandler() {
        val terminal = CompletableDeferred<PendingWrite>()
        val forwardedTerminal = CompletableDeferred<Unit>()
        val inactive = CompletableDeferred<Unit>()
        private val installed = CompletableDeferred<ChannelHandlerContext>()
        private var responseNumber = 0
        private var pending: PendingWrite? = null

        override fun handlerAdded(context: ChannelHandlerContext) {
            installed.complete(context)
        }

        override fun write(context: ChannelHandlerContext, message: Any, promise: ChannelPromise) {
            if (message is HttpResponse) responseNumber++
            if (message is LastHttpContent) {
                if (responseNumber == targetResponse) {
                    check(pending == null)
                    val write = PendingWrite(message, promise)
                    pending = write
                    terminal.complete(write)
                    return
                }
                promise.addListener { result ->
                    if (result.isSuccess) forwardedTerminal.complete(Unit)
                    else forwardedTerminal.completeExceptionally(result.cause())
                }
            }
            context.write(message, promise)
        }

        override fun channelInactive(context: ChannelHandlerContext) {
            // 故意保留 gate 所有权与 pending promise，测试关闭路径；fixture finally 统一清理。
            inactive.complete(Unit)
            context.fireChannelInactive()
        }

        suspend fun barrier() = onEventLoop { Unit }

        suspend fun release() = onEventLoop { context ->
            val write = assertNotNull(pending)
            pending = null
            context.write(write.message, write.promise)
            context.flush()
        }

        suspend fun dispose() {
            if (!installed.isCompleted) return
            onEventLoop { context ->
                try {
                    pending?.let { write ->
                        pending = null
                        try {
                            ReferenceCountUtil.release(write.message)
                        } finally {
                            write.promise.tryFailure(ClosedChannelException())
                        }
                    }
                } finally {
                    context.channel().close()
                }
            }
        }

        private suspend fun <T> onEventLoop(block: (ChannelHandlerContext) -> T): T {
            val context = installed.bounded()
            val completed = CompletableDeferred<T>()
            context.channel().eventLoop().execute {
                try {
                    completed.complete(block(context))
                } catch (cause: Throwable) {
                    completed.completeExceptionally(cause)
                }
            }
            return completed.bounded()
        }
    }

    private data class PendingWrite(val message: Any, val promise: ChannelPromise)
    private data class WireResponse(val status: Int, val headers: Map<String, String>, val body: String)

    private fun request(path: String) =
        "GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: keep-alive\r\n\r\n"

    private suspend fun Socket.send(request: String) = withContext(Dispatchers.IO) {
        getOutputStream().apply { write(request.toByteArray(Charsets.US_ASCII)); flush() }
    }

    private fun readResponse(input: InputStream): WireResponse {
        val status = readLine(input).split(' ')[1].toInt()
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            headers[line.substringBefore(':').lowercase(Locale.ROOT)] = line.substringAfter(':').trim()
        }
        val bytes = ByteArrayOutputStream()
        if (headers["transfer-encoding"] == "chunked") {
            while (true) {
                val size = readLine(input).substringBefore(';').toInt(16)
                if (size == 0) {
                    while (readLine(input).isNotEmpty()) Unit
                    break
                }
                bytes.write(readBytes(input, size))
                assertEquals("", readLine(input))
            }
        } else {
            bytes.write(readBytes(input, assertNotNull(headers["content-length"]).toInt()))
        }
        return WireResponse(status, headers, bytes.toString(Charsets.UTF_8))
    }

    private fun readBytes(input: InputStream, size: Int): ByteArray = input.readNBytes(size).also {
        assertEquals(size, it.size, "response ended before all body bytes arrived")
    }

    private fun readLine(input: InputStream): String {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            check(byte >= 0) { "connection closed before the complete HTTP response" }
            if (byte == '\n'.code) {
                check(line.lastOrNull() == '\r')
                return line.dropLast(1).toString()
            }
            check(line.length < 8_192) { "unexpectedly long HTTP header" }
            line.append(byte.toChar())
        }
    }

    companion object {
        private const val WAIT_MS = 5_000L
        private const val PROBE_MS = 25L
        private const val EVENT = "data: {\"token\":\"hello\"}\n\n"
        private const val DONE = "data: [DONE]\n\n"

        private fun body(stream: Boolean) = if (stream) EVENT + DONE else "{\"ok\":true}"
        private suspend fun <T> CompletableDeferred<T>.bounded(): T = withTimeout(WAIT_MS) { await() }
    }
}
