package io.github.siloverse.fix.session

import io.github.siloverse.fix.codec.FixDecoder
import io.github.siloverse.fix.codec.FixEncoder
import io.github.siloverse.fix.codec.SOH
import io.github.siloverse.fix.message.FixField
import io.github.siloverse.fix.message.FixMessage
import io.github.siloverse.fix.message.MsgType
import io.github.siloverse.fix.message.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/** One test per rule in [FixSession]'s KDoc, plus the scenarios from the day-0 exercise. */
class FixSessionTest {

    private fun readable(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1).replace(SOH, '|')
    private fun decode(bytes: ByteArray) = FixDecoder.decode(bytes)
    private val ORDER = "D"
    private val REPORT = "8"

    // ---- rule 7: logon -------------------------------------------------------------------

    @Test
    fun `7 - logon handshake makes both sides active and the acceptor's interval wins`() {
        val link = SessionLink()
        link.connect()

        assertEquals(SessionState.ACTIVE, link.broker.state)
        assertEquals(SessionState.ACTIVE, link.venue.state)
        assertEquals(listOf(SessionEvent.LoggedOn), link.brokerEvents)
        assertEquals(listOf(SessionEvent.LoggedOn), link.venueEvents)
        assertEquals(Duration.ofSeconds(20), link.broker.heartBtInt)
        assertEquals(Duration.ofSeconds(20), link.venue.heartBtInt)
        assertEquals(2L, link.broker.nextOutbound)
        assertEquals(2L, link.broker.nextExpectedInbound)
    }

    @Test
    fun `7 - logon with reset takes both stores to 1 and is echoed`() {
        val link = SessionLink()
        link.connect()
        repeat(5) { link.brokerSends(ORDER, 11 to "o$it") }
        link.pump()
        link.dropConnection()
        assertEquals(7L, link.broker.nextOutbound)

        link.broker = FixSession(link.broker.config.copy(resetOnLogon = true), link.brokerStore)
        link.collect(link.broker, link.broker.connect(link.now))
        val logon = decode(link.toVenue.first())
        assertEquals("Y", logon[Tag.RESET_SEQ_NUM_FLAG])
        assertEquals(1L, logon.seqNum)
        link.collect(link.venue, link.venue.connect(link.now))
        link.pump()

        assertEquals(SessionState.ACTIVE, link.venue.state)
        assertEquals(2L, link.broker.nextOutbound)
        assertEquals(2L, link.venue.nextOutbound)
        assertEquals(2L, link.broker.nextExpectedInbound)
        assertTrue(link.venueStore.outbound(1).size == 1)
    }

    @Test
    fun `7 - a first message that is not a Logon ends the connection`() {
        val link = SessionLink()
        link.collect(link.venue, link.venue.connect(link.now))
        val stray = FixEncoder.encode(FixMessage.create(ORDER, "BROKER", "VENUE", 1, link.now))

        link.collect(link.venue, link.venue.onBytes(stray, link.now))

        assertEquals(SessionState.DISCONNECTED, link.venue.state)
        assertTrue(link.venueEvents.any { it is SessionEvent.Disconnect })
        assertEquals(MsgType.LOGOUT, decode(link.toBroker.single()).msgType)
    }

    // ---- rule 1: store before send -------------------------------------------------------

    @Test
    fun `1 - an outbound message is numbered and stored before it is returned for sending`() {
        val link = SessionLink()
        link.connect()

        link.brokerSends(ORDER, 11 to "ord-1", 55 to "ACME")

        val bytes = link.toVenue.single()
        val sent = decode(bytes)
        assertEquals(2L, sent.seqNum)
        assertEquals("ord-1", sent[11])
        val stored = link.brokerStore.outbound(2, 2).single()
        assertTrue(stored.bytes.contentEquals(bytes))
        assertEquals(3L, link.broker.nextOutbound)
    }

    // ---- rule 2: in order --------------------------------------------------------------------

    @Test
    fun `2 - in-order application messages reach the peer's application and advance expected`() {
        val link = SessionLink()
        link.connect()

        link.brokerSends(ORDER, 11 to "a")
        link.brokerSends(ORDER, 11 to "b")
        link.pump()

        assertEquals(listOf("a", "b"), link.venueApp.map { it[11] })
        assertEquals(4L, link.venue.nextExpectedInbound)
        assertTrue(link.venueEvents.none { it is SessionEvent.Disconnect })
    }

    // ---- rule 3: gap -> hold and resend request ---------------------------------------------

    @Test
    fun `3 - a gap holds the later message, sends one ResendRequest, and drains after the resend`() {
        val link = SessionLink()
        link.connect()

        link.brokerSends(ORDER, 11 to "a")   // seq 2
        link.brokerSends(ORDER, 11 to "b")   // seq 3
        link.brokerSends(ORDER, 11 to "c")   // seq 4
        link.toVenue.removeFirst()           // lose "a" on the wire
        link.deliverOne(link.venue)          // venue gets 3 while expecting 2
        link.deliverOne(link.venue)          // and 4

        assertTrue(link.venueApp.isEmpty(), "held messages must not reach the application")
        val requests = link.toBroker.map(::decode).filter { it.msgType == MsgType.RESEND_REQUEST }
        assertEquals(1, requests.size, "exactly one ResendRequest for one gap")
        assertEquals("2", requests.single()[Tag.BEGIN_SEQ_NO])
        assertEquals("0", requests.single()[Tag.END_SEQ_NO])
        assertEquals(listOf(SessionEvent.GapDetected(2, 0)), link.venueEvents.filterIsInstance<SessionEvent.GapDetected>())

        link.pump()   // broker answers the resend; venue drains

        assertEquals(listOf("a", "b", "c"), link.venueApp.map { it[11] })
        assertEquals("Y", link.venueApp[0][Tag.POSS_DUP_FLAG])
        assertNull(link.venueApp[1][Tag.POSS_DUP_FLAG])
        assertEquals(5L, link.venue.nextExpectedInbound)
        assertEquals(SessionState.ACTIVE, link.venue.state)
    }

    // ---- rule 4: too low -------------------------------------------------------------------

    @Test
    fun `4a - a PossDup message below expected is ignored`() {
        val link = SessionLink()
        link.connect()
        link.brokerSends(ORDER, 11 to "a")
        val original = link.toVenue.first()
        link.pump()

        val duplicate = FixEncoder.encode(SessionMessages.asPossDup(decode(original), link.now))
        link.collect(link.venue, link.venue.onBytes(duplicate, link.now))

        assertEquals(1, link.venueApp.size)
        assertEquals(SessionState.ACTIVE, link.venue.state)
        assertTrue(link.toBroker.isEmpty())
    }

    @Test
    fun `4b - a fresh message below expected ends the session with a Logout that says why`() {
        val link = SessionLink()
        link.connect()
        link.brokerSends(ORDER, 11 to "a")
        link.pump()

        val tooLow = FixEncoder.encode(FixMessage.create(ORDER, "BROKER", "VENUE", 2, link.now, listOf(FixField(11, "again"))))
        link.collect(link.venue, link.venue.onBytes(tooLow, link.now))

        val logout = decode(link.toBroker.single())
        assertEquals(MsgType.LOGOUT, logout.msgType)
        assertEquals("MsgSeqNum too low, expecting 3 but received 2", logout[Tag.TEXT])
        assertEquals(SessionState.DISCONNECTED, link.venue.state)
        assertEquals(1, link.venueApp.size)
    }

    // ---- rule 5: answering a resend ----------------------------------------------------------

    @Test
    fun `5 - a resend replays application messages with PossDup and gap-fills admin messages and holes`() {
        val link = SessionLink()
        link.connect()
        // broker: 2 = heartbeat, 3 = order, 4 = heartbeat, 5 = order, 6 = order
        link.advance(Duration.ofSeconds(20)); link.collect(link.broker, link.broker.onTick(link.now))
        link.brokerSends(ORDER, 11 to "x")
        link.advance(Duration.ofSeconds(20)); link.collect(link.broker, link.broker.onTick(link.now))
        link.brokerSends(ORDER, 11 to "y")
        link.brokerSends(ORDER, 11 to "z")
        assertEquals(7L, link.broker.nextOutbound)
        link.toVenue.clear()   // venue saw none of 2..6

        val request = FixEncoder.encode(SessionMessages.resendRequest("VENUE", "BROKER", 2, link.now, 2, 0))
        link.collect(link.broker, link.broker.onBytes(request, link.now))

        val replay = link.toVenue.map(::decode)
        assertEquals(listOf("4", ORDER, "4", ORDER, ORDER), replay.map { it.msgType })
        assertEquals(listOf(2L, 3L, 4L, 5L, 6L), replay.map { it.seqNum })
        assertEquals("3", replay[0][Tag.NEW_SEQ_NO]);  assertEquals("Y", replay[0][Tag.GAP_FILL_FLAG])
        assertEquals("5", replay[2][Tag.NEW_SEQ_NO])
        assertTrue(replay.all { it[Tag.POSS_DUP_FLAG] == "Y" })
        assertTrue(replay.filter { it.msgType == ORDER }.all { it[Tag.ORIG_SENDING_TIME] != null })
        assertEquals(7L, link.broker.nextOutbound, "a replay must not consume new numbers")

        link.pump()   // venue applies the replay
        assertEquals(listOf("x", "y", "z"), link.venueApp.map { it[11] })
        assertEquals(7L, link.venue.nextExpectedInbound)
    }

    @Test
    fun `5 - a resend request is capped at the last number actually sent`() {
        val link = SessionLink()
        link.connect()
        link.brokerSends(ORDER, 11 to "a")   // 2
        link.toVenue.clear()

        val beyond = FixEncoder.encode(SessionMessages.resendRequest("VENUE", "BROKER", 2, link.now, 3, 9))
        link.collect(link.broker, link.broker.onBytes(beyond, link.now))
        assertTrue(link.toVenue.isEmpty(), "nothing above the last sent number exists to replay")

        val open = FixEncoder.encode(SessionMessages.resendRequest("VENUE", "BROKER", 3, link.now, 1, 0))
        link.collect(link.broker, link.broker.onBytes(open, link.now))
        val replay = link.toVenue.map(::decode)
        assertEquals(listOf(1L, 2L), replay.map { it.seqNum })
        assertEquals(MsgType.SEQUENCE_RESET, replay[0].msgType)   // the Logon at 1 is admin: gap-filled
        assertEquals("2", replay[0][Tag.NEW_SEQ_NO])
        assertEquals(ORDER, replay[1].msgType)
    }

    // ---- rule 6: timers -----------------------------------------------------------------------

    @Test
    fun `6 - silence produces a heartbeat, then a test request, then a disconnect`() {
        val link = SessionLink()
        link.connect()
        link.toBroker.clear(); link.toVenue.clear()

        link.advance(Duration.ofSeconds(19)); link.collect(link.broker, link.broker.onTick(link.now))
        assertTrue(link.toVenue.isEmpty(), "nothing before the interval")

        link.advance(Duration.ofSeconds(1)); link.collect(link.broker, link.broker.onTick(link.now))
        assertEquals(MsgType.HEARTBEAT, decode(link.toVenue.removeFirst()).msgType)

        link.advance(Duration.ofSeconds(4)); link.collect(link.broker, link.broker.onTick(link.now))   // 24 s of inbound silence
        val testRequest = decode(link.toVenue.removeFirst())
        assertEquals(MsgType.TEST_REQUEST, testRequest.msgType)
        assertEquals("TEST-1", testRequest[Tag.TEST_REQ_ID])

        link.advance(Duration.ofSeconds(24)); link.collect(link.broker, link.broker.onTick(link.now))
        assertTrue(link.brokerEvents.any { it is SessionEvent.Disconnect && it.reason == "no response to TestRequest" })
        assertEquals(SessionState.DISCONNECTED, link.broker.state)
    }

    @Test
    fun `6 - a heartbeat answering the test request cancels the disconnect`() {
        val link = SessionLink()
        link.connect()
        link.toBroker.clear(); link.toVenue.clear()

        link.advance(Duration.ofSeconds(24)); link.collect(link.broker, link.broker.onTick(link.now))
        link.pump()   // venue answers the TestRequest with a Heartbeat carrying 112

        assertTrue(link.venueStore.outbound(2).map { decode(it.bytes) }.any { it.msgType == MsgType.HEARTBEAT && it[Tag.TEST_REQ_ID] == "TEST-1" })
        link.advance(Duration.ofSeconds(24)); link.collect(link.broker, link.broker.onTick(link.now))
        assertEquals(SessionState.ACTIVE, link.broker.state)
    }

    @Test
    fun `6 - no Logon within the timeout drops the connection`() {
        val link = SessionLink()
        link.collect(link.broker, link.broker.connect(link.now))
        link.advance(Duration.ofSeconds(10))
        link.collect(link.broker, link.broker.onTick(link.now))

        assertEquals(SessionState.DISCONNECTED, link.broker.state)
        assertTrue(link.brokerEvents.any { it is SessionEvent.Disconnect })
    }

    // ---- housekeeping ----------------------------------------------------------------------

    @Test
    fun `garbled bytes are counted and ignored, never answered`() {
        val link = SessionLink()
        link.connect()
        link.toBroker.clear()

        link.collect(link.venue, link.venue.onBytes("8=FIX.4.4|9=5|35=0|10=999|".replace('|', SOH).toByteArray(), link.now))

        assertEquals(1, link.venueEvents.filterIsInstance<SessionEvent.Garbled>().size)
        assertTrue(link.toBroker.isEmpty())
        assertEquals(SessionState.ACTIVE, link.venue.state)
    }

    @Test
    fun `a CompID mismatch ends the session`() {
        val link = SessionLink()
        link.connect()
        val wrong = FixEncoder.encode(FixMessage.create(ORDER, "INTRUDER", "VENUE", 2, link.now))

        link.collect(link.venue, link.venue.onBytes(wrong, link.now))

        assertEquals(SessionState.DISCONNECTED, link.venue.state)
        assertEquals(MsgType.LOGOUT, decode(link.toBroker.single()).msgType)
    }

    @Test
    fun `logout handshake - initiator sends, peer acknowledges, both disconnect once`() {
        val link = SessionLink()
        link.connect()

        link.collect(link.broker, link.broker.logout(link.now, "end of day"))
        assertEquals(SessionState.LOGOUT_SENT, link.broker.state)
        link.pump()

        assertEquals(SessionState.DISCONNECTED, link.venue.state)
        assertEquals(SessionState.DISCONNECTED, link.broker.state)
        assertEquals(1, link.venueEvents.count { it is SessionEvent.Disconnect })
        assertEquals(1, link.brokerEvents.count { it is SessionEvent.Disconnect })
        assertEquals("end of day", (link.venueEvents.single { it is SessionEvent.PeerLoggedOut } as SessionEvent.PeerLoggedOut).text)
        assertEquals(2, link.venueStore.outbound(1).size, "venue sent Logon and one Logout only")
    }

    @Test
    fun `an in-order PossDup message still reaches the application with its flag`() {
        val link = SessionLink()
        link.connect()
        val report = FixEncoder.encode(SessionMessages.asPossDup(FixMessage.create(REPORT, "VENUE", "BROKER", 2, link.now, listOf(FixField(17, "exec-1"))), link.now))

        link.collect(link.broker, link.broker.onBytes(report, link.now))

        assertEquals("exec-1", link.brokerApp.single()[17])
        assertEquals("Y", link.brokerApp.single()[Tag.POSS_DUP_FLAG])
    }

    // ---- the stage exit test and the day-0 exercise -----------------------------------------

    @Test
    fun `stage B exit - ten messages, connection lost between 6 and 7, reconnect, each seen once`() {
        val link = SessionLink()
        link.connect()
        link.venueSends(REPORT, 17 to "e1"); link.venueSends(REPORT, 17 to "e2"); link.venueSends(REPORT, 17 to "e3")
        link.pump()
        // venue heartbeat at 5, then reports 6..9 (seq), broker never sees any of it
        link.advance(Duration.ofSeconds(20)); link.collect(link.venue, link.venue.onTick(link.now))
        (4..7).forEach { link.venueSends(REPORT, 17 to "e$it") }
        link.dropConnection()
        assertEquals(3, link.brokerApp.size)

        link.connect()   // same stores, new transport

        val seen = link.brokerApp.map { it[17] }
        assertEquals(listOf("e1", "e2", "e3", "e4", "e5", "e6", "e7"), seen)
        assertEquals(listOf(null, null, null, "Y", "Y", "Y", "Y"), link.brokerApp.map { it[Tag.POSS_DUP_FLAG] })
        assertEquals(SessionState.ACTIVE, link.broker.state)
        assertEquals(link.venue.nextOutbound, link.broker.nextExpectedInbound)
        assertEquals(link.broker.nextOutbound, link.venue.nextExpectedInbound)
        val replayed = link.venueStore.outbound(5).map { decode(it.bytes) }
        assertEquals(MsgType.HEARTBEAT, replayed.first().msgType, "the heartbeat at 5 was gap-filled, never resent")
    }

    @Test
    fun `day 0 exercise - broker restarts after a crash with stale counters and recovers the gap`() {
        val link = SessionLink()
        link.connect()
        repeat(3) { link.brokerSends(ORDER, 11 to "o$it") }
        link.pump()
        (1..4).forEach { link.venueSends(REPORT, 17 to "e$it") }
        link.toBroker.clear()   // the broker's process dies with these unread
        link.dropConnection()
        val brokerOutBefore = link.broker.nextOutbound
        val brokerInBefore = link.broker.nextExpectedInbound

        link.restartBroker()    // new process, same store on disk
        assertEquals(brokerOutBefore, link.broker.nextOutbound, "numbering survives a crash")
        link.collect(link.broker, link.broker.connect(link.now))
        val logon = decode(link.toVenue.first())
        assertEquals(brokerOutBefore, logon.seqNum)
        link.collect(link.venue, link.venue.connect(link.now))
        link.pump()

        assertEquals(listOf("e1", "e2", "e3", "e4"), link.brokerApp.map { it[17] })
        assertTrue(link.brokerApp.all { it[Tag.POSS_DUP_FLAG] == "Y" })
        assertTrue(link.brokerEvents.any { it == SessionEvent.GapDetected(brokerInBefore, 0) })
        assertEquals(SessionState.ACTIVE, link.broker.state)
        assertEquals(SessionState.ACTIVE, link.venue.state)
    }
}
