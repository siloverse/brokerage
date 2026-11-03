package io.github.siloverse.fix.codec

import io.github.siloverse.fix.codec.Fixtures.readable
import io.github.siloverse.fix.codec.Fixtures.wire
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FixFramerTest {

    private val both = wire(Fixtures.HEARTBEAT) + wire(Fixtures.NEW_ORDER_SINGLE)

    @Test
    fun `two messages fed in seven byte chunks come out as exactly two frames`() {
        val framer = FixFramer()
        val frames = both.toList().chunked(7).flatMap { framer.feed(it.toByteArray()) }

        assertEquals(2, frames.size)
        assertEquals(Fixtures.HEARTBEAT, readable(frames[0]))
        assertEquals(Fixtures.NEW_ORDER_SINGLE, readable(frames[1]))
        assertEquals(0, framer.pending)
    }

    @Test
    fun `two messages in one chunk come out as two frames`() {
        val frames = FixFramer().feed(both)

        assertEquals(2, frames.size)
        assertEquals("0", FixDecoder.decode(frames[0]).msgType)
        assertEquals("D", FixDecoder.decode(frames[1]).msgType)
    }

    @Test
    fun `a partial message is held back until the rest arrives`() {
        val framer = FixFramer()
        val bytes = wire(Fixtures.HEARTBEAT)

        assertTrue(framer.feed(bytes.copyOfRange(0, 40)).isEmpty())
        assertEquals(40, framer.pending)
        val frames = framer.feed(bytes.copyOfRange(40, bytes.size))

        assertEquals(1, frames.size)
        assertEquals(Fixtures.HEARTBEAT, readable(frames[0]))
    }

    @Test
    fun `garbage before a message is dropped`() {
        val frames = FixFramer().feed("junk\u0001more".toByteArray() + wire(Fixtures.HEARTBEAT))

        assertEquals(1, frames.size)
        assertEquals(Fixtures.HEARTBEAT, readable(frames[0]))
    }

    @Test
    fun `a message with an unreadable length is skipped and the next one is found`() {
        val broken = wire("8=FIX.4.4|9=abc|35=0|10=000|")
        val frames = FixFramer().feed(broken + wire(Fixtures.HEARTBEAT))

        assertEquals(1, frames.size)
        assertEquals(Fixtures.HEARTBEAT, readable(frames[0]))
    }

    @Test
    fun `a frame cut by the length field is handed to the decoder as is`() {
        // The framer trusts 9=; a wrong value yields a frame the decoder then rejects.
        val wrongLength = wire(Fixtures.HEARTBEAT.replace("9=54|", "9=50|"))
        val frames = FixFramer().feed(wrongLength)

        assertEquals(1, frames.size)
        assertEquals(wrongLength.size - 4, frames[0].size)
        org.junit.jupiter.api.Assertions.assertThrows(FixGarbledException::class.java) {
            FixDecoder.decode(frames[0])
        }
    }
}
