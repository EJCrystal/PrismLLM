package dev.soven.prismllm.fake

import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class RecordingSocks5Proxy(
    private val refusing: Boolean = false,
    private val username: String? = null,
    private val password: String? = null,
) : AutoCloseable {
    data class Target(val host: String, val port: Int, val addressType: Int)

    val targets = ConcurrentLinkedQueue<Target>()
    val authenticatedUsers = ConcurrentLinkedQueue<String>()
    private val sockets = ConcurrentLinkedQueue<Socket>()
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val url: String get() = "socks5://127.0.0.1:${listener.localPort}"

    init {
        executor.submit {
            while (!listener.isClosed) {
                val socket = runCatching { listener.accept() }.getOrNull() ?: break
                sockets.add(socket)
                executor.submit { runCatching { socket.use(::tunnel) } }
            }
        }
    }

    private fun tunnel(client: Socket) {
        client.soTimeout = 5_000
        val input = DataInputStream(client.getInputStream())
        val output = client.getOutputStream()
        require(input.readUnsignedByte() == 5)
        val methods = ByteArray(input.readUnsignedByte()).also(input::readFully)
        val method = if (username == null) 0 else 2
        if (methods.none { it.toInt() == method }) {
            output.write(byteArrayOf(5, -1))
            output.flush()
            return
        }
        output.write(byteArrayOf(5, method.toByte()))
        output.flush()
        if (method == 2) {
            require(input.readUnsignedByte() == 1)
            val receivedUsername = ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8)
            val receivedPassword = ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8)
            val matches = receivedUsername == username && receivedPassword == password
            output.write(byteArrayOf(1, if (matches) 0 else 1))
            output.flush()
            if (!matches) return
            authenticatedUsers.add(receivedUsername)
        }
        require(input.readUnsignedByte() == 5)
        require(input.readUnsignedByte() == 1)
        require(input.readUnsignedByte() == 0)
        val addressType = input.readUnsignedByte()
        val host = when (addressType) {
            1 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully)).hostAddress
            3 -> ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.US_ASCII)
            4 -> InetAddress.getByAddress(ByteArray(16).also(input::readFully)).hostAddress
            else -> error("unsupported SOCKS5 address type")
        }
        val port = input.readUnsignedShort()
        targets.add(Target(host, port, addressType))
        if (refusing) {
            output.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0))
            output.flush()
            return
        }
        Socket().use { upstream ->
            sockets.add(upstream)
            val targetHost = if (host == "socks-target.invalid") "127.0.0.1" else host
            upstream.connect(InetSocketAddress(targetHost, port), 2_000)
            output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
            output.flush()
            client.soTimeout = 0
            val upload = executor.submit {
                runCatching { input.copyTo(upstream.getOutputStream()) }
                runCatching { upstream.shutdownOutput() }
            }
            try {
                upstream.getInputStream().copyTo(output)
            } finally {
                client.close()
                upload.cancel(true)
            }
        }
    }

    override fun close() {
        listener.close()
        sockets.forEach { runCatching { it.close() } }
        executor.shutdownNow()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }
}
