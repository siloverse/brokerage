package io.github.siloverse.venue.fix

import io.github.siloverse.fix.message.FixField
import io.github.siloverse.fix.message.FixMessage
import io.github.siloverse.fix.session.FixInitiator
import io.github.siloverse.fix.session.SessionConfig
import io.github.siloverse.fix.session.SessionEvent
import io.github.siloverse.fix.session.SessionListener
import io.github.siloverse.fix.session.SessionRole
import io.github.siloverse.fix.store.InMemoryMessageStore
import io.github.siloverse.venue.engine.EngineConfig
import io.github.siloverse.venue.engine.VenueEngine
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The measurement from the 0.3 design answers: does a slow broker slow the venue?
 *
 * The broker's listener sleeps 50 ms per report and never catches up. The venue fires a
 * burst of orders' worth of reports. We time, on the venue side, how long `driver.send`
 * takes, in two modes: writing inline under the session lock, and through the bounded
 * outbound queue. Numbers are printed for the journal; the assertions only pin the
 * qualitative outcome.
 */
class SlowConsumerMeasurementTest {

    private val cleanup = mutableListOf<() -> Unit>()

    @AfterEach
    fun tearDown() = cleanup.reversed().forEach { runCatching(it) }

    private class Result(val mode: String, val sends: Int, val p50Micros: Long, val p99Micros: Long, val maxMicros: Long, val heartbeatsOut: Int, val events: List<SessionEvent>, val senderStuck: Boolean)

    private companion object {
        /** Small kernel buffers so the burst cannot hide in them: ~100 reports instead of ~20,000. */
        const val SOCKET_BUFFER = 16 * 1024
    }

    private fun measure(outboundQueueLimit: Int, reports: Int, consumerDelayMillis: Long, runFor: Duration): Result {
        val engine = VenueEngine(EngineConfig(priceStep = 0, startPrice = 10_00, sessionClose = LocalTime.MAX, zone = ZoneOffset.UTC))
        val venueStore = InMemoryMessageStore()
        val server = VenueFixServer(engine, SessionConfig("VENUE", "BROKER", SessionRole.ACCEPTOR, Duration.ofSeconds(1)), venueStore, 0, Duration.ofMillis(20), outboundQueueLimit = outboundQueueLimit, socketBufferBytes = SOCKET_BUFFER)
        server.start(); cleanup += { server.stop() }

        val received = AtomicInteger()
        val brokerEvents = CopyOnWriteArrayList<SessionEvent>()
        val broker = FixInitiator(
            SessionConfig("BROKER", "VENUE", SessionRole.INITIATOR, Duration.ofSeconds(1)), InMemoryMessageStore(),
            object : SessionListener {
                override fun onMessage(message: FixMessage) { Thread.sleep(consumerDelayMillis); received.incrementAndGet() }   // the slow consumer
                override fun onEvent(event: SessionEvent) { brokerEvents += event }
            },
            "127.0.0.1", server.port, backoff = Duration.ofMillis(100), socketBufferBytes = SOCKET_BUFFER,
        )
        broker.start(); cleanup += { broker.stop() }
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (!broker.isActive && System.nanoTime() < deadline) Thread.sleep(10)
        check(broker.isActive) { "broker never logged on" }

        // The venue sends reports as fast as it can on its own thread (an inline write may block
        // for good); each send is timed on the venue side. We stop looking after [runFor].
        val micros = LongArray(reports)
        val sent = AtomicInteger()
        val sender = Thread {
            while (sent.get() < reports && server.driver.isActive) {
                val i = sent.get()
                val t = System.nanoTime()
                val ok = runCatching { server.driver.send("8", listOf(FixField(17, "e$i"), FixField(11, "c$i"), FixField(37, "V$i"), FixField(150, "F"), FixField(39, "2"))) }.isSuccess
                micros[i] = (System.nanoTime() - t) / 1_000
                sent.incrementAndGet()
                if (!ok) break
            }
        }.apply { isDaemon = true; start() }
        sender.join(runFor.toMillis())
        val inFlight = if (sender.isAlive) (System.nanoTime() - sendStartedAt(micros, sent.get())) / 1_000 else 0L
        return finish(if (outboundQueueLimit == 0) "inline" else "queue=$outboundQueueLimit", micros, sent.get(), venueStore, server, stuckSendMicros = inFlight, senderStuck = sender.isAlive)
    }

    /** Approximate: the wall-clock start of the send that has not returned (we timestamp only on return). */
    private fun sendStartedAt(micros: LongArray, sent: Int): Long = System.nanoTime() - (micros.copyOf(sent).sum() * 1_000)

    private fun finish(mode: String, micros: LongArray, sent: Int, store: InMemoryMessageStore, server: VenueFixServer, stuckSendMicros: Long, senderStuck: Boolean): Result {
        val sorted = micros.copyOf(sent).sorted()
        fun pct(p: Double) = if (sorted.isEmpty()) 0L else sorted[((sorted.size - 1) * p).toInt()]
        val heartbeats = store.outbound(1).count { String(it.bytes, Charsets.ISO_8859_1).contains("\u000135=0\u0001") }
        return Result(mode, sent, pct(0.5), pct(0.99), maxOf(sorted.lastOrNull() ?: 0, stuckSendMicros), heartbeats, server.events.toList(), senderStuck)
    }

    @Test
    fun `a slow broker against inline writes versus the bounded queue`() {
        val inline = measure(outboundQueueLimit = 0, reports = 20_000, consumerDelayMillis = 50, runFor = Duration.ofSeconds(3))
        tearDown(); cleanup.clear()
        val queued = measure(outboundQueueLimit = 2_000, reports = 20_000, consumerDelayMillis = 50, runFor = Duration.ofSeconds(3))

        for (r in listOf(inline, queued)) {
            println("MEASUREMENT mode=${r.mode} sends=${r.sends} send p50=${r.p50Micros}us p99=${r.p99Micros}us max=${r.maxMicros}us senderStuck=${r.senderStuck} heartbeatsOut=${r.heartbeatsOut} events=${r.events}")
        }

        // Inline: once the kernel buffers are full, the venue's send blocks inside the session lock and stays
        // blocked; the ticker cannot run, so the venue cannot even heartbeat. The slow broker has wedged the venue.
        assertTrue(inline.senderStuck, "inline sender should be wedged in a socket write: ${inline.events}")
        // Queue: every send returns quickly, the queue fills, and the slow consumer is dropped on purpose.
        assertTrue(!queued.senderStuck, "queued sender must never wedge")
        assertTrue(queued.p99Micros < 5_000, "queued p99 ${queued.p99Micros}us")
        assertTrue(queued.events.any { it is SessionEvent.Disconnect && it.reason.startsWith("slow consumer") }, "the slow consumer is dropped: ${queued.events}")
    }
}
