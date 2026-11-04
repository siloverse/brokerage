package io.github.siloverse.fix.session

import io.github.siloverse.fix.message.FixField
import io.github.siloverse.fix.transport.Transport
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The only place in the session layer with threads. Owns one [FixSession] and, while
 * attached, one [Transport]: a reader thread feeds bytes in, a ticker feeds time in, and
 * [send] feeds application messages in. Every [SessionOutput] is applied in order under
 * one lock: bytes to the outbound queue, messages and events to the [listener].
 *
 * Outbound bytes go through a bounded queue drained by a writer thread, so a peer that
 * stops reading cannot block the session (and with it the heartbeats) through a blocked
 * socket write. When the queue is full the peer is a slow consumer and the connection is
 * dropped; the store still has every message, so the peer recovers by resend when it is
 * back. [outboundQueueLimit] = 0 writes inline under the lock instead, for comparison.
 *
 * Lifetime: one driver per session, many [attach]es over time (one per connection).
 */
class SessionDriver(
    val session: FixSession,
    private val listener: SessionListener,
    private val clock: () -> Instant = Instant::now,
    private val tickInterval: Duration = Duration.ofMillis(250),
    private val outboundQueueLimit: Int = 10_000,
) {
    private val lock = ReentrantLock()
    private val ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fix-ticker-${session.config.senderCompId}").apply { isDaemon = true } }
    private var transport: Transport? = null
    private var reader: Thread? = null
    private var writer: Thread? = null
    private var outbound: LinkedBlockingQueue<ByteArray>? = null
    private var tick: ScheduledFuture<*>? = null
    private var disconnected = CountDownLatch(0)

    /** How many chunks are waiting for the writer right now, and the most that ever waited. */
    val queueDepth: Int get() = outbound?.size ?: 0
    val maxQueueDepth = AtomicLong()

    val isActive: Boolean get() = lock.withLock { session.state == SessionState.ACTIVE }

    /**
     * Bind a fresh connection: runs the session's connect, feeds [alreadyRead] (bytes an
     * acceptor consumed while identifying the peer) before the reader thread can read
     * anything newer, then starts the reader, the writer and the ticker.
     */
    fun attach(transport: Transport, alreadyRead: List<ByteArray> = emptyList()) {
        lock.withLock {
            check(this.transport == null) { "already attached" }
            this.transport = transport
            disconnected = CountDownLatch(1)
            if (outboundQueueLimit > 0) {
                val queue = LinkedBlockingQueue<ByteArray>(outboundQueueLimit)
                outbound = queue
                writer = Thread({ writeLoop(transport, queue) }, "fix-writer-${session.config.senderCompId}").apply { isDaemon = true; start() }
            }
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

    /** Never takes the lock: on a dead socket it just closes it and lets the reader report the drop. */
    private fun writeLoop(transport: Transport, queue: LinkedBlockingQueue<ByteArray>) {
        while (true) {
            val bytes = queue.take()
            if (bytes === EOF) { runCatching { transport.close() }; return }
            try {
                transport.write(bytes)
            } catch (e: IOException) {
                runCatching { transport.close() }
                return
            }
        }
    }

    /** Must hold the lock. Bytes first, so a Logout is queued before the transport closes. */
    private fun apply(output: SessionOutput) {
        for (bytes in output.bytesToSend) {
            if (!enqueue(bytes)) return
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
            if (event is SessionEvent.Disconnect) closeTransport(graceful = true)
        }
    }

    /** Must hold the lock. Returns false when the connection was dropped because of this write. */
    private fun enqueue(bytes: ByteArray): Boolean {
        val transport = this.transport ?: return false
        val queue = outbound
        if (queue == null) {
            return try {
                transport.write(bytes); true
            } catch (e: IOException) {
                dropTransport("write failed: ${e.message}"); false
            }
        }
        if (!queue.offer(bytes)) {
            dropTransport("slow consumer: outbound queue full (limit $outboundQueueLimit)")
            return false
        }
        val depth = queue.size.toLong()
        maxQueueDepth.accumulateAndGet(depth, ::maxOf)
        return true
    }

    /** Must hold the lock. Used when the transport died on us, not when the session asked. */
    private fun dropTransport(reason: String) {
        if (transport == null) return
        closeTransport(graceful = false)
        session.disconnected()
        listener.onEvent(SessionEvent.Disconnect(reason))
    }

    /**
     * Must hold the lock. Graceful: let the writer flush what is queued (a Logout, usually)
     * for up to a second before the socket goes. Not graceful: close now; whatever is
     * queued is in the store anyway.
     */
    private fun closeTransport(graceful: Boolean) {
        val current = transport ?: return
        transport = null
        tick?.cancel(false)
        tick = null
        val queue = outbound
        val writerThread = writer
        outbound = null
        writer = null
        if (graceful && queue != null && writerThread != null) {
            queue.offer(EOF)
            writerThread.join(1_000)
        }
        runCatching { current.close() }
        queue?.clear()
        disconnected.countDown()
    }

    private companion object {
        val EOF = ByteArray(0)
    }
}
