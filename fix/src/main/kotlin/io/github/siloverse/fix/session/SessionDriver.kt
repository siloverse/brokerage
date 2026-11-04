package io.github.siloverse.fix.session

import io.github.siloverse.fix.message.FixField
import io.github.siloverse.fix.transport.Transport
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The only place in the session layer with threads. Owns one [FixSession] and, while
 * attached, one [Transport]: a reader thread feeds bytes in, a ticker feeds time in, and
 * [send] feeds application messages in. Every [SessionOutput] is applied in order under
 * one lock: bytes to the transport, messages and events to the [listener].
 *
 * Lifetime: one driver per session, many [attach]es over time (one per connection).
 */
class SessionDriver(
    val session: FixSession,
    private val listener: SessionListener,
    private val clock: () -> Instant = Instant::now,
    private val tickInterval: Duration = Duration.ofMillis(250),
) {
    private val lock = ReentrantLock()
    private val ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fix-ticker-${session.config.senderCompId}").apply { isDaemon = true } }
    private var transport: Transport? = null
    private var reader: Thread? = null
    private var tick: ScheduledFuture<*>? = null
    private var disconnected = CountDownLatch(0)

    val isActive: Boolean get() = lock.withLock { session.state == SessionState.ACTIVE }

    /**
     * Bind a fresh connection: runs the session's connect, feeds [alreadyRead] (bytes an
     * acceptor consumed while identifying the peer) before the reader thread can read
     * anything newer, then starts the reader and the ticker.
     */
    fun attach(transport: Transport, alreadyRead: List<ByteArray> = emptyList()) {
        lock.withLock {
            check(this.transport == null) { "already attached" }
            this.transport = transport
            disconnected = CountDownLatch(1)
            apply(session.connect(clock()))
            for (bytes in alreadyRead) {
                if (this.transport == null) return
                apply(session.onBytes(bytes, clock()))
            }
        }
        reader = Thread({ readLoop(transport) }, "fix-reader-${session.config.senderCompId}").apply { isDaemon = true; start() }
        tick = ticker.scheduleAtFixedRate({ runCatching { lock.withLock { apply(session.onTick(clock())) } } }, tickInterval.toMillis(), tickInterval.toMillis(), TimeUnit.MILLISECONDS)
    }

    fun send(msgType: String, body: List<FixField>) = lock.withLock { apply(session.send(msgType, body, clock())) }

    fun logout(text: String? = null) = lock.withLock { apply(session.logout(clock(), text)) }

    /** Blocks until the current connection is gone (peer closed, timeout, logout, or [detach]). */
    fun awaitDisconnected(timeout: Duration): Boolean = disconnected.await(timeout.toMillis(), TimeUnit.MILLISECONDS)

    /** Drop the connection now, without a Logout. The session keeps its counters. */
    fun detach() = lock.withLock { dropTransport("detached") }

    fun close() {
        detach()
        ticker.shutdownNow()
    }

    // ---- internals -------------------------------------------------------------------------

    private fun readLoop(transport: Transport) {
        while (true) {
            val bytes = try {
                transport.read()
            } catch (e: IOException) {
                lock.withLock { if (this.transport === transport) dropTransport("read failed: ${e.message}") }
                return
            }
            if (bytes.isEmpty()) {
                lock.withLock { if (this.transport === transport) dropTransport("peer closed") }
                return
            }
            lock.withLock {
                if (this.transport !== transport) return
                apply(session.onBytes(bytes, clock()))
            }
        }
    }

    /** Must hold the lock. Writes first, so a Logout reaches the wire before the transport closes. */
    private fun apply(output: SessionOutput) {
        val transport = this.transport
        for (bytes in output.bytesToSend) {
            try {
                transport?.write(bytes)
            } catch (e: IOException) {
                dropTransport("write failed: ${e.message}")
                return
            }
        }
        for (message in output.applicationMessages) {
            try {
                listener.onMessage(message)
            } catch (e: Exception) {
                runCatching { listener.onEvent(SessionEvent.ListenerFailed("message ${message.seqNum} (${message.msgType})", e)) }
            }
        }
        for (event in output.events) {
            runCatching { listener.onEvent(event) }
            if (event is SessionEvent.Disconnect) closeTransport()
        }
    }

    /** Must hold the lock. Used when the transport died on us, not when the session asked. */
    private fun dropTransport(reason: String) {
        if (transport == null) return
        closeTransport()
        session.disconnected()
        listener.onEvent(SessionEvent.Disconnect(reason))
    }

    /** Must hold the lock. */
    private fun closeTransport() {
        val current = transport ?: return
        transport = null
        tick?.cancel(false)
        tick = null
        runCatching { current.close() }
        disconnected.countDown()
    }
}
