package io.github.siloverse.fix.session

import io.github.siloverse.fix.message.FixField
import io.github.siloverse.fix.store.InMemoryMessageStore
import io.github.siloverse.fix.transport.LoopbackTransport
import io.github.siloverse.fix.transport.Transport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch

/** The outbound queue: what it protects, and what it does when it is full. */
class SessionDriverQueueTest {

    private fun config(sender: String, target: String, role: SessionRole) =
        SessionConfig(sender, target, role, Duration.ofSeconds(1))

    /** A transport whose writes block until released: the kernel buffer is full and the peer is not reading. */
    private class StuckTransport(private val inner: Transport) : Transport by inner {
        val gate = CountDownLatch(1)
        @Volatile var stuck = false
        override fun write(bytes: ByteArray) {
            if (stuck) gate.await()
            inner.write(bytes)
        }
    }

    @Test
    fun `a full outbound queue drops the slow consumer instead of blocking the session`() {
        val (a, b) = LoopbackTransport.pair()
        val stuck = StuckTransport(a)
        val listener = RecordingListener()
        val broker = SessionDriver(FixSession(config("BROKER", "VENUE", SessionRole.INITIATOR), InMemoryMessageStore()), listener, tickInterval = Duration.ofMillis(20), outboundQueueLimit = 5)
        val venue = SessionDriver(FixSession(config("VENUE", "BROKER", SessionRole.ACCEPTOR), InMemoryMessageStore()), RecordingListener(), tickInterval = Duration.ofMillis(20))
        venue.attach(b)
        broker.attach(stuck)
        assertTrue(listener.await { loggedOn() })

        stuck.stuck = true
        // The writer thread blocks on the first of these; the rest queue up; the sixth finds the queue full.
        repeat(10) { runCatching { broker.send("D", listOf(FixField(11, "o$it"))) } }

        assertTrue(listener.await { disconnected() })
        val reason = (listener.events.first { it is SessionEvent.Disconnect } as SessionEvent.Disconnect).reason
        assertTrue(reason.startsWith("slow consumer"), reason)
        assertEquals(SessionState.DISCONNECTED, broker.session.state)
        assertTrue(broker.session.nextOutbound >= 7, "every message was stored before the drop, so a resend can recover them")
        stuck.gate.countDown()
        broker.close(); venue.close()
    }

    @Test
    fun `a graceful logout reaches the peer through the queue before the socket closes`() {
        val (a, b) = LoopbackTransport.pair()
        val brokerListener = RecordingListener()
        val venueListener = RecordingListener()
        val broker = SessionDriver(FixSession(config("BROKER", "VENUE", SessionRole.INITIATOR), InMemoryMessageStore()), brokerListener, tickInterval = Duration.ofMillis(20), outboundQueueLimit = 100)
        val venue = SessionDriver(FixSession(config("VENUE", "BROKER", SessionRole.ACCEPTOR), InMemoryMessageStore()), venueListener, tickInterval = Duration.ofMillis(20), outboundQueueLimit = 100)
        venue.attach(b)
        broker.attach(a)
        assertTrue(brokerListener.await { loggedOn() })

        broker.logout("bye")

        assertTrue(venueListener.await { events.any { it is SessionEvent.PeerLoggedOut && it.text == "bye" } })
        assertTrue(brokerListener.await { disconnected() })
        assertTrue(venueListener.await { disconnected() })
        broker.close(); venue.close()
    }

    @Test
    fun `inline mode writes under the lock and still works for a well-behaved peer`() {
        val (a, b) = LoopbackTransport.pair()
        val brokerListener = RecordingListener()
        val venueListener = RecordingListener()
        val broker = SessionDriver(FixSession(config("BROKER", "VENUE", SessionRole.INITIATOR), InMemoryMessageStore()), brokerListener, tickInterval = Duration.ofMillis(20), outboundQueueLimit = 0)
        val venue = SessionDriver(FixSession(config("VENUE", "BROKER", SessionRole.ACCEPTOR), InMemoryMessageStore()), venueListener, tickInterval = Duration.ofMillis(20), outboundQueueLimit = 0)
        venue.attach(b)
        broker.attach(a)
        assertTrue(brokerListener.await { loggedOn() })

        repeat(20) { broker.send("D", listOf(FixField(11, "o$it"))) }

        assertTrue(venueListener.await { messages.size == 20 })
        assertEquals(0, broker.queueDepth)
        broker.close(); venue.close()
    }
}
