package io.github.siloverse.fix.session

import io.github.siloverse.fix.message.FixField
import io.github.siloverse.fix.message.Tag
import io.github.siloverse.fix.store.InMemoryMessageStore
import io.github.siloverse.fix.transport.LoopbackTransport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** The driver over an in-memory transport pair: threads and lock, no sockets. */
class SessionDriverTest {

    private val brokerListener = RecordingListener()
    private val venueListener = RecordingListener()
    private val brokerStore = InMemoryMessageStore()
    private val venueStore = InMemoryMessageStore()
    private val broker = SessionDriver(FixSession(SessionConfig("BROKER", "VENUE", SessionRole.INITIATOR, Duration.ofSeconds(1)), brokerStore), brokerListener, tickInterval = Duration.ofMillis(20))
    private val venue = SessionDriver(FixSession(SessionConfig("VENUE", "BROKER", SessionRole.ACCEPTOR, Duration.ofSeconds(1)), venueStore), venueListener, tickInterval = Duration.ofMillis(20))

    @AfterEach
    fun tearDown() { broker.close(); venue.close() }

    private fun connectBoth() {
        val (a, b) = LoopbackTransport.pair()
        venue.attach(b)
        broker.attach(a)
        assertTrue(brokerListener.await { loggedOn() } && venueListener.await { loggedOn() }, "logon over the loopback")
    }

    @Test
    fun `logon, application traffic both ways, and heartbeats from the ticker`() {
        connectBoth()

        broker.send("D", listOf(FixField(11, "ord-1")))
        venue.send("8", listOf(FixField(17, "exec-1")))

        assertTrue(venueListener.await { messages.any { it[11] == "ord-1" } })
        assertTrue(brokerListener.await { messages.any { it[17] == "exec-1" } })

        // 1 s interval, 20 ms ticks: within 3 s both sides must have heartbeated at least once.
        assertTrue(brokerListener.await(Duration.ofSeconds(3)) { brokerStore.outbound(1).any { String(it.bytes, Charsets.ISO_8859_1).contains("\u000135=0\u0001") } })
        assertTrue(venueListener.await(Duration.ofSeconds(3)) { venueStore.outbound(1).any { String(it.bytes, Charsets.ISO_8859_1).contains("\u000135=0\u0001") } })
        assertTrue(broker.isActive && venue.isActive)
    }

    @Test
    fun `the peer closing the transport surfaces as one Disconnect and the session keeps its counters`() {
        connectBoth()
        broker.send("D", listOf(FixField(11, "ord-1")))
        assertTrue(venueListener.await { messages.size == 1 })
        val outBefore = broker.session.nextOutbound

        venue.detach()

        assertTrue(brokerListener.await { disconnected() })
        assertTrue(venueListener.await { disconnected() })
        assertEquals(1, brokerListener.events.count { it is SessionEvent.Disconnect })
        assertEquals(SessionState.DISCONNECTED, broker.session.state)
        assertEquals(outBefore, broker.session.nextOutbound)
    }

    @Test
    fun `a clean logout reaches the peer before the transport closes`() {
        connectBoth()

        broker.logout("done")

        assertTrue(venueListener.await { events.any { it is SessionEvent.PeerLoggedOut && it.text == "done" } })
        assertTrue(brokerListener.await { disconnected() })
        assertTrue(venueListener.await { disconnected() })
    }

    @Test
    fun `a second attach after a drop continues the sequence numbers on both sides`() {
        connectBoth()
        broker.send("D", listOf(FixField(11, "a")))
        assertTrue(venueListener.await { messages.size == 1 })

        broker.detach()
        assertTrue(venueListener.await { disconnected() })

        val (a, b) = LoopbackTransport.pair()
        venue.attach(b)
        broker.attach(a)
        assertTrue(brokerListener.await { loggedOn(2) } && venueListener.await { loggedOn(2) })

        broker.send("D", listOf(FixField(11, "b")))
        assertTrue(venueListener.await { messages.size == 2 })
        assertEquals(listOf("a", "b"), venueListener.messages.map { it[11] })
        assertEquals(venue.session.nextExpectedInbound, broker.session.nextOutbound)
        assertTrue(venueListener.messages.none { it[Tag.POSS_DUP_FLAG] == "Y" })
    }

    @Test
    fun `an injected clock drives the timers deterministically`() {
        var now = Instant.parse("2026-11-03T10:00:00Z")
        val listener = RecordingListener()
        val store = InMemoryMessageStore()
        val driver = SessionDriver(FixSession(SessionConfig("BROKER", "VENUE", SessionRole.INITIATOR, Duration.ofSeconds(30), logonTimeout = Duration.ofSeconds(10)), store), listener, clock = { now }, tickInterval = Duration.ofMillis(10))
        val (a, _) = LoopbackTransport.pair()
        driver.attach(a)   // nobody answers the Logon

        now = now.plusSeconds(9)
        Thread.sleep(50)
        assertTrue(listener.events.none { it is SessionEvent.Disconnect })

        now = now.plusSeconds(2)
        assertTrue(listener.await { disconnected() })
        assertEquals("no Logon within PT10S", (listener.events.single { it is SessionEvent.Disconnect } as SessionEvent.Disconnect).reason)
        driver.close()
    }
}
