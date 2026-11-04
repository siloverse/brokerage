package io.github.siloverse.fix.session

import io.github.siloverse.fix.codec.FixDecoder
import io.github.siloverse.fix.codec.FixFramer
import io.github.siloverse.fix.codec.FixGarbledException
import io.github.siloverse.fix.message.MsgType
import io.github.siloverse.fix.message.Tag
import io.github.siloverse.fix.store.MessageStore
import io.github.siloverse.fix.transport.SocketTransport
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The listening side. One [ServerSocket]; each incoming connection is read up to its
 * first message, which must be a Logon, and routed by its SenderCompID to the session
 * registered for that counterparty. A counterparty that already has a live connection,
 * or one nobody registered, is dropped without a reply.
 */
/** [socketBufferBytes] shrinks each connection's kernel buffers: only for the slow-consumer drill. */
class FixAcceptor(private val port: Int = 0, private val socketBufferBytes: Int? = null) {

    private class Registered(val driver: SessionDriver)

    private val sessions = ConcurrentHashMap<String, Registered>()
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var loop: Thread? = null

    /** The port actually bound, useful when constructed with 0. */
    val localPort: Int get() = server?.localPort ?: port

    /** Register the session that will serve connections from [config]'s TargetCompID. */
    fun register(config: SessionConfig, store: MessageStore, listener: SessionListener, outboundQueueLimit: Int = 10_000): SessionDriver {
        require(config.role == SessionRole.ACCEPTOR) { "acceptor needs an ACCEPTOR config" }
        val driver = SessionDriver(FixSession(config, store), listener, outboundQueueLimit = outboundQueueLimit)
        check(sessions.putIfAbsent(config.targetCompId, Registered(driver)) == null) { "session for ${config.targetCompId} already registered" }
        return driver
    }

    fun driverFor(counterparty: String): SessionDriver? = sessions[counterparty]?.driver

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val socket = ServerSocket(port)
        server = socket
        loop = Thread({ acceptLoop(socket) }, "fix-acceptor-$localPort").apply { isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        sessions.values.forEach { it.driver.close() }
        loop?.join(2_000)
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val transport = try {
                SocketTransport.accept(socket, socketBufferBytes)
            } catch (e: IOException) {
                if (running.get()) continue else return
            }
            Thread({ route(transport) }, "fix-acceptor-handshake").apply { isDaemon = true; start() }
        }
    }

    /** Read until the first frame, identify the counterparty, hand everything to its driver. */
    private fun route(transport: SocketTransport) {
        val framer = FixFramer()
        val received = mutableListOf<ByteArray>()
        var firstFrame: ByteArray? = null
        try {
            while (firstFrame == null) {
                val chunk = transport.read()
                if (chunk.isEmpty()) { transport.close(); return }
                received += chunk
                firstFrame = framer.feed(chunk).firstOrNull()
            }
            val logon = FixDecoder.decode(firstFrame)
            val counterparty = logon[Tag.SENDER_COMP_ID]
            val registered = if (logon.msgType == MsgType.LOGON && counterparty != null) sessions[counterparty] else null
            if (registered == null || registered.driver.session.state != SessionState.DISCONNECTED) {
                transport.close()
                return
            }
            registered.driver.attach(transport, alreadyRead = received)
        } catch (e: FixGarbledException) {
            transport.close()
        } catch (e: IOException) {
            transport.close()
        }
    }
}
