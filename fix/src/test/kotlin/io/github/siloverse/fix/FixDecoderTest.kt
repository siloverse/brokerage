package io.github.siloverse.fix

import io.github.siloverse.fix.Fixtures.wire
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class FixDecoderTest {

    @Test
    fun `decodes a heartbeat and reads its header`() {
        val msg = FixDecoder.decode(wire(Fixtures.HEARTBEAT))

        assertEquals("0", msg.msgType)
        assertEquals(2L, msg.seqNum)
        assertEquals("BROKER", msg[Tag.SENDER_COMP_ID])
        assertEquals("VENUE", msg[Tag.TARGET_COMP_ID])
        assertEquals(Instant.parse("2026-11-03T10:00:00Z"), msg.getInstant(Tag.SENDING_TIME))
        assertEquals("254", msg[Tag.CHECK_SUM])
    }

    @Test
    fun `decodes a NewOrderSingle and reads typed body fields`() {
        val msg = FixDecoder.decode(wire(Fixtures.NEW_ORDER_SINGLE))

        assertEquals("D", msg.msgType)
        assertEquals("ord-1", msg[11])
        assertEquals(100, msg.getInt(38))
        assertEquals(100L, msg.getLong(38))
        assertEquals("10.00", msg[44])
        assertEquals(null, msg[999])
    }

    @Test
    fun `round trips through encoder and decoder`() {
        val original = Fixtures.newOrderSingle
        val bytes = FixEncoder.encode(original)
        val decoded = FixDecoder.decode(bytes)

        assertArrayEquals(bytes, FixEncoder.encode(decoded))
        assertEquals(original.fields, decoded.fields.filterNot { it.tag in setOf(8, 9, 10) })
    }

    @Test
    fun `rejects a wrong checksum as garbled`() {
        val corrupt = Fixtures.HEARTBEAT.replace("10=254|", "10=255|")

        val error = assertThrows(FixGarbledException::class.java) { FixDecoder.decode(wire(corrupt)) }
        assertEquals("CheckSum 255 but computed 254", error.message)
    }

    @Test
    fun `rejects a wrong body length as garbled`() {
        val corrupt = Fixtures.HEARTBEAT.replace("9=54|", "9=53|")

        assertThrows(FixGarbledException::class.java) { FixDecoder.decode(wire(corrupt)) }
    }

    @Test
    fun `rejects a frame that does not start with BeginString`() {
        assertThrows(FixGarbledException::class.java) { FixDecoder.decode(wire("35=0|10=000|")) }
    }

    @Test
    fun `rejects a frame without a trailing SOH`() {
        assertThrows(FixGarbledException::class.java) { FixDecoder.decode(wire(Fixtures.HEARTBEAT.dropLast(1))) }
    }
}
