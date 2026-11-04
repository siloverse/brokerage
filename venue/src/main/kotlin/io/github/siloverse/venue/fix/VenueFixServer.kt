package io.github.siloverse.venue.fix

import io.github.siloverse.fix.message.FixMessage
import io.github.siloverse.fix.session.FixAcceptor
import io.github.siloverse.fix.session.SessionConfig
import io.github.siloverse.fix.session.SessionDriver
import io.github.siloverse.fix.session.SessionEvent
import io.github.siloverse.fix.session.SessionListener
import io.github.siloverse.fix.session.SessionRole
import io.github.siloverse.fix.store.MessageStore
import io.github.siloverse.venue.engine.VenueEngine
import io.github.siloverse.venue.engine.VenueReply
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The venue's FIX face: one acceptor, one session for the broker, one engine. Inbound
 * order messages go to the engine on the session's thread; a ticker drives fills and
 * expiry. Replies go out on the session when it is active and are dropped otherwise:
 * a broker that is not connected asks for status when it comes back (plan 2.6).
 */
class VenueFixServer(
    val engine: VenueEngine,
    sessionConfig: SessionConfig,
    store: MessageStore,
    fixPort: Int,
    private val tickInterval: Duration = Duration.ofMillis(100),
    private val clock: () -> Instant = Instant::now,
) {
    private val acceptor = FixAcceptor(fixPort)
    private val lock = Any()
    private val ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "venue-engine-ticker").apply { isDaemon = true } }
    val driver: SessionDriver

    init {
        require(sessionConfig.role == SessionRole.ACCEPTOR)
        driver = acceptor.register(sessionConfig, store, object : SessionListener {
            override fun onMessage(message: FixMessage) {
                val replies = synchronized(lock) { engine.handle(message, clock()) }
                send(replies)
            }
            override fun onEvent(event: SessionEvent) { /* stage D: log and count */ }
        })
    }

    val port: Int get() = acceptor.localPort

    fun start() {
        acceptor.start()
        ticker.scheduleAtFixedRate({ runCatching { send(synchronized(lock) { engine.tick(clock()) }) } }, tickInterval.toMillis(), tickInterval.toMillis(), TimeUnit.MILLISECONDS)
    }

    fun stop() {
        ticker.shutdownNow()
        acceptor.stop()
    }

    private fun send(replies: List<VenueReply>) {
        if (replies.isEmpty()) return
        for (reply in replies) {
            if (!driver.isActive) return
            runCatching { driver.send(reply.msgType, reply.body) }
        }
    }
}
