package io.github.siloverse.fix

import io.github.siloverse.fix.Fixtures.readable
import io.github.siloverse.fix.Fixtures.wire
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FixEncoderTest {

    @Test
    fun `encodes a heartbeat byte for byte`() {
        val bytes = FixEncoder.encode(Fixtures.heartbeat)

        assertEquals(Fixtures.HEARTBEAT, readable(bytes))
        assertArrayEquals(wire(Fixtures.HEARTBEAT), bytes)
        assertEquals(76, bytes.size)
    }

    @Test
    fun `encodes a NewOrderSingle byte for byte`() {
        val bytes = FixEncoder.encode(Fixtures.newOrderSingle)

        assertEquals(Fixtures.NEW_ORDER_SINGLE, readable(bytes))
        assertEquals(150, bytes.size)
    }

    @Test
    fun `drops any 8, 9 or 10 on the input and recomputes them`() {
        val decoded = FixDecoder.decode(wire(Fixtures.HEARTBEAT))
        val tampered = decoded.with(FixField(Tag.CHECK_SUM, "000")).with(FixField(Tag.BODY_LENGTH, "1"))

        assertArrayEquals(wire(Fixtures.HEARTBEAT), FixEncoder.encode(tampered))
    }

    @Test
    fun `checksum is zero padded to three digits`() {
        // Vary the sending time until some checksum falls below 100; every one must still print as three digits.
        val base = java.time.Instant.parse("2026-11-03T10:00:00Z")
        var found = false
        for (second in 0L until 600L) {
            val msg = FixMessage.create("0", "A", "B", 1, base.plusSeconds(second))
            val text = readable(FixEncoder.encode(msg))
            val checksum = text.substringAfterLast("10=").dropLast(1)
            assertEquals(3, checksum.length, "checksum must always be three characters: $text")
            if (checksum.startsWith("0")) found = true
        }
        assert(found) { "expected at least one checksum below 100 in 600 candidates" }
    }
}
