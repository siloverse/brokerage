package io.github.siloverse.fix.session

import io.github.siloverse.fix.message.FixField
import io.github.siloverse.fix.store.MessageStore
import io.github.siloverse.fix.transport.SocketTransport
import java.io.IOException
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The connecting side. Keeps one [SessionDriver] alive against [host]:[port]: connect,
 * attach, wait for the connection to die, back off, connect again. The session and its
 * store survive every reconnect, which is what makes the resend after a drop work.
 */
class FixInitiator(
    config: SessionConfig,
    store: MessageStore,
    listener: SessionListener,
    private val host: String,
    private val port: Int,
    private val backoff: Duration = Duration.ofSeconds(1),
    private val maxBackoff: Duration = Duration.ofSeconds(30),
    private val socketBufferBytes: Int? = null,
) {
    init { require(config.role == SessionRole.INITIATOR) { "initiator needs an INITIATOR config" } }

    val driver = SessionDriver(FixSession(config, store), listener)
    private val running = AtomicBoolean(false)
    private var loop: Thread? = null

    val isActive: Boolean get() = driver.isActive

    fun start() {
        if (!running.compareAndSet(false, true)) return
        loop = Thread(::connectLoop, "fix-initiator-${driver.session.config.senderCompId}").apply { isDaemon = true; start() }
    }

    /** Logs out if connected, stops reconnecting, releases the threads. */
    fun stop() {
        running.set(false)
        if (driver.isActive) runCatching { driver.logout("shutdown") }
        driver.awaitDisconnected(Duration.ofSeconds(2))
        driver.close()
        loop?.interrupt()
    }

    fun send(msgType: String, body: List<FixField>) = driver.send(msgType, body)

    private fun connectLoop() {
        var delay = backoff
        while (running.get()) {
            val transport = try {
                SocketTransport.connect(host, port, bufferBytes = socketBufferBytes)
            } catch (e: IOException) {
                if (!sleep(delay)) return
                delay = minOf(delay.multipliedBy(2), maxBackoff)
                continue
            }
            delay = backoff
            driver.attach(transport)
            while (running.get() && !driver.awaitDisconnected(Duration.ofSeconds(1))) { /* connected: wait */ }
            if (running.get() && !sleep(delay)) return
        }
    }

    private fun sleep(duration: Duration): Boolean = try {
        Thread.sleep(duration.toMillis()); true
    } catch (e: InterruptedException) {
        false
    }
}
