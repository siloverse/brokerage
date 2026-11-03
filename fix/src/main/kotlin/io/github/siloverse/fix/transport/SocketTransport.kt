package io.github.siloverse.fix.transport

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** A [Transport] over one TCP socket. Blocking reads on the caller's thread, as the session expects. */
class SocketTransport(private val socket: Socket) : Transport {

    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()
    private val buffer = ByteArray(READ_CHUNK)

    override fun read(): ByteArray {
        val n = input.read(buffer)
        return if (n < 0) ByteArray(0) else buffer.copyOf(n)
    }

    override fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    override fun close() = socket.close()

    companion object {
        private const val READ_CHUNK = 8 * 1024

        /** Initiator side: connect to [host]:[port]. */
        fun connect(host: String, port: Int, timeoutMillis: Int = 5_000): SocketTransport {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), timeoutMillis)
            return SocketTransport(socket)
        }

        /** Acceptor side: wait for one connection on [server]. */
        fun accept(server: ServerSocket): SocketTransport {
            val socket = server.accept()
            socket.tcpNoDelay = true
            return SocketTransport(socket)
        }
    }
}
