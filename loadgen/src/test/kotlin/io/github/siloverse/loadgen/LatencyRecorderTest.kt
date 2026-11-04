package io.github.siloverse.loadgen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LatencyRecorderTest {

    @Test
    fun `only ok outcomes shape the percentiles, the rest are counted`() {
        val r = LatencyRecorder()
        for (i in 1..100L) r.record(RequestType.ORDER, Outcome.OK, i * 1_000)
        r.record(RequestType.ORDER, Outcome.REJECTED, 1)          // fast failure
        r.record(RequestType.ORDER, Outcome.TIMEOUT, 5_000_000)   // slow failure
        val s = r.snapshot(RequestType.ORDER)!!
        assertEquals(100, s.ok); assertEquals(1, s.rejected); assertEquals(1, s.timedOut); assertEquals(102, s.sent)
        assertTrue(s.p50Micros in 49_000..51_000, "p50 ${s.p50Micros}")
        assertTrue(s.p99Micros in 98_000..100_100, "p99 ${s.p99Micros}")
        assertTrue(s.maxMicros < 101_000, "the timeout's 5 s must not appear as a max: ${s.maxMicros}")
    }

    @Test
    fun `a type never recorded has no stats`() {
        assertNull(LatencyRecorder().snapshot(RequestType.QUOTE))
    }
}
