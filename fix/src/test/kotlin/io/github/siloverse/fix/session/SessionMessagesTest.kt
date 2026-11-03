package io.github.siloverse.fix.session

import io.github.siloverse.fix.codec.FixDecoder
import io.github.siloverse.fix.codec.FixEncoder
import io.github.siloverse.fix.codec.SOH
import io.github.siloverse.fix.message.FixMessage
import io.github.siloverse.fix.message.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class SessionMessagesTest {

    private val time = Instant.parse("2026-11-03T10:00:00Z")
    private val later = Instant.parse("2026-11-03T10:05:00Z")

    /** Encode, decode back, and return the readable wire form: proves the builder output survives the codec. */
    private fun wire(message: FixMessage): String =
        String(FixEncoder.encode(message), Charsets.ISO_8859_1).replace(SOH, '|')
            .also { FixDecoder.decode(FixEncoder.encode(message)) }

    @Test
    fun `logon carries encrypt method, heartbeat interval and no reset flag by default`() {
        val text = wire(SessionMessages.logon("BROKER", "VENUE", 1, time, heartBtInt = 30))

        assertTrue(text.startsWith("8=FIX.4.4|9="))
        assertTrue("|35=A|49=BROKER|56=VENUE|34=1|52=20261103-10:00:00.000|98=0|108=30|10=" in text)
        assertFalse("141=" in text)
    }

    @Test
    fun `logon with reset carries 141=Y`() {
        assertTrue("|108=30|141=Y|" in wire(SessionMessages.logon("B", "V", 1, time, 30, resetSeqNum = true)))
    }

    @Test
    fun `heartbeat is bare unless it answers a test request`() {
        assertTrue("|35=0|49=B|56=V|34=7|52=20261103-10:00:00.000|10=" in wire(SessionMessages.heartbeat("B", "V", 7, time)))
        assertTrue("|52=20261103-10:00:00.000|112=probe-1|10=" in wire(SessionMessages.heartbeat("B", "V", 8, time, "probe-1")))
    }

    @Test
    fun `test request carries its id`() {
        assertTrue("|35=1|49=B|56=V|34=9|52=20261103-10:00:00.000|112=probe-2|10=" in wire(SessionMessages.testRequest("B", "V", 9, time, "probe-2")))
    }

    @Test
    fun `resend request defaults to everything from begin`() {
        assertTrue("|35=2|49=B|56=V|34=302|52=20261103-10:00:00.000|7=4712|16=0|10=" in wire(SessionMessages.resendRequest("B", "V", 302, time, 4712)))
        assertTrue("|7=4712|16=4720|" in wire(SessionMessages.resendRequest("B", "V", 302, time, 4712, 4720)))
    }

    @Test
    fun `gap fill is a sequence reset with 123=Y, 36 and 43=Y`() {
        val text = wire(SessionMessages.sequenceResetGapFill("V", "B", 4715, time, newSeqNo = 4717))

        assertTrue("|35=4|49=V|56=B|34=4715|52=20261103-10:00:00.000|123=Y|36=4717|43=Y|10=" in text)
    }

    @Test
    fun `reset mode is a sequence reset with 123=N and no poss dup`() {
        val text = wire(SessionMessages.sequenceResetReset("V", "B", 10, time, newSeqNo = 5000))

        assertTrue("|35=4|" in text && "|123=N|36=5000|" in text)
        assertFalse("43=" in text)
    }

    @Test
    fun `reject names the offending message and the reason`() {
        val text = wire(SessionMessages.reject("B", "V", 5, time, refSeqNum = 4, reason = "missing tag 108", refTagId = 108, refMsgType = "A"))

        assertTrue("|35=3|49=B|56=V|34=5|52=20261103-10:00:00.000|45=4|371=108|372=A|58=missing tag 108|10=" in text)
    }

    @Test
    fun `logout is bare or carries text`() {
        assertTrue("|35=5|49=B|56=V|34=6|52=20261103-10:00:00.000|10=" in wire(SessionMessages.logout("B", "V", 6, time)))
        assertTrue("|58=MsgSeqNum too low|10=" in wire(SessionMessages.logout("B", "V", 6, time, "MsgSeqNum too low")))
    }

    @Test
    fun `asPossDup marks a resend and keeps the original sequence number`() {
        val original = SessionMessages.logon("B", "V", 42, time, 30)
        val resend = SessionMessages.asPossDup(original, later)

        assertEquals(42L, resend.seqNum)
        assertEquals("Y", resend[Tag.POSS_DUP_FLAG])
        assertEquals("20261103-10:00:00.000", resend[Tag.ORIG_SENDING_TIME])
        assertEquals("20261103-10:05:00.000", resend[Tag.SENDING_TIME])
        assertTrue("|34=42|43=Y|52=20261103-10:05:00.000|122=20261103-10:00:00.000|98=0|" in wire(resend))
    }

    @Test
    fun `asPossDup applied twice does not stack flags and keeps the first sending time`() {
        val original = SessionMessages.heartbeat("B", "V", 3, time)
        val twice = SessionMessages.asPossDup(SessionMessages.asPossDup(original, later), later.plusSeconds(60))

        assertEquals(1, twice.fields.count { it.tag == Tag.POSS_DUP_FLAG })
        assertEquals(1, twice.fields.count { it.tag == Tag.ORIG_SENDING_TIME })
        assertEquals("20261103-10:00:00.000", twice[Tag.ORIG_SENDING_TIME])
        assertEquals("20261103-10:06:00.000", twice[Tag.SENDING_TIME])
        assertNull(twice[999])
    }
}
