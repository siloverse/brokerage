package io.github.siloverse.fix.session

import io.github.siloverse.fix.message.FixField
import io.github.siloverse.fix.message.Tag
import io.github.siloverse.fix.store.FileMessageStore
import io.github.siloverse.fix.store.InMemoryMessageStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration

/** Real sockets, real threads, a real reconnect. */
class FixAcceptorInitiatorTest {

    @TempDir
    lateinit var dir: Path

    private val acceptor = FixAcceptor(0)
    private val venueListener = RecordingListener()
    private val brokerListener = RecordingListener()
    private lateinit var initiator: FixInitiator

    @AfterEach
    fun tearDown() {
        if (::initiator.isInitialized) initiator.stop()
        acceptor.stop()
    }

    private fun startBoth(brokerStore: FileMessageStore) {
        acceptor.register(SessionConfig("VENUE", "BROKER", SessionRole.ACCEPTOR, Duration.ofSeconds(2)), InMemoryMessageStore(), venueListener)
        acceptor.start()
        initiator = FixInitiator(
            SessionConfig("BROKER", "VENUE", SessionRole.INITIATOR, Duration.ofSeconds(2)), brokerStore, brokerListener,
            host = "127.0.0.1", port = acceptor.localPort, backoff = Duration.ofMillis(100),
        )
        initiator.start()
        assertTrue(brokerListener.await { loggedOn() } && venueListener.await { loggedOn() }, "logon over TCP")
    }

    @Test
    fun `orders flow over TCP and the venue routes by SenderCompID`() {
        startBoth(FileMessageStore(dir.resolve("broker")))

        initiator.send("D", listOf(FixField(11, "ord-1"), FixField(55, "ACME")))
        acceptor.driverFor("BROKER")!!.send("8", listOf(FixField(17, "exec-1")))

        assertTrue(venueListener.await { messages.any { it[11] == "ord-1" } })
        assertTrue(brokerListener.await { messages.any { it[17] == "exec-1" } })
    }

    @Test
    fun `the connection drops mid-stream, the initiator reconnects, nothing is lost or doubled`() {
        startBoth(FileMessageStore(dir.resolve("broker")))
        val venue = acceptor.driverFor("BROKER")!!

        initiator.send("D", listOf(FixField(11, "o1")))
        assertTrue(venueListener.await { messages.size == 1 })

        // The venue's side of the socket dies right after it sent two reports; TCP may or may
        // not have delivered them. Either way the broker must end up with each exactly once.
        venue.send("8", listOf(FixField(17, "e1")))
        venue.send("8", listOf(FixField(17, "e2")))
        venue.detach()
        assertTrue(brokerListener.await { disconnected() })

        // Backoff is 100 ms: the initiator comes back and logs on; a gap, if any, is recovered.
        assertTrue(brokerListener.await(Duration.ofSeconds(10)) { loggedOn(2) }, "reconnected")
        assertTrue(brokerListener.await { messages.map { it[17] }.containsAll(listOf("e1", "e2")) })
        assertEquals(listOf("e1", "e2"), brokerListener.messages.map { it[17] })

        initiator.send("D", listOf(FixField(11, "o2")))
        assertTrue(venueListener.await { messages.size == 2 })
        assertEquals(listOf("o1", "o2"), venueListener.messages.map { it[11] })
        assertTrue(venueListener.messages.none { it[Tag.POSS_DUP_FLAG] == "Y" })
    }

    @Test
    fun `a second connection for a counterparty that is already connected is dropped silently`() {
        startBoth(FileMessageStore(dir.resolve("broker")))

        val intruder = FixInitiator(
            SessionConfig("BROKER", "VENUE", SessionRole.INITIATOR, Duration.ofSeconds(2)), InMemoryMessageStore(), RecordingListener(),
            host = "127.0.0.1", port = acceptor.localPort, backoff = Duration.ofMillis(100),
        )
        intruder.start()
        Thread.sleep(500)

        assertTrue(initiator.isActive, "the first session is untouched")
        assertEquals(1, venueListener.events.count { it == SessionEvent.LoggedOn })
        intruder.stop()
    }
}
