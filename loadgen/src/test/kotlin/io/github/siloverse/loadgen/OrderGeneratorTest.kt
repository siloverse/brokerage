package io.github.siloverse.loadgen

import io.github.siloverse.brokerage.orders.domain.Side
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OrderGeneratorTest {

    @Test
    fun `the resting share matches the cancel ratio and resting orders are priced away from the mid`() {
        val g = OrderGenerator(Profile.BASELINE)
        val orders = (1..1000).map { g.next() }
        val resting = generateSequence { g.nextToCancel() }.toSet()
        assertTrue(resting.size in 150..250, "20 % should rest, got ${resting.size}")
        val mid = Profile.BASELINE.referencePrice
        for (o in orders) {
            val crosses = (o.side == Side.BUY && o.limitPrice.minorUnits > mid) || (o.side == Side.SELL && o.limitPrice.minorUnits < mid)
            assertEquals(!crosses, o.clientOrderId in resting, "every order either crosses or rests: $o")
        }
    }

    @Test
    fun `the skewed profile puts most orders on the first instrument`() {
        val g = OrderGenerator(Profile.SKEWED)
        val hot = (1..1000).count { g.next().instrument.symbol == Profile.TEN_INSTRUMENTS.first() }
        assertTrue(hot in 850..950, "90 % expected on the hot instrument, got $hot")
    }

    @Test
    fun `the same seed makes the same orders`() {
        val a = OrderGenerator(Profile.BASELINE, seed = 7)
        val b = OrderGenerator(Profile.BASELINE, seed = 7)
        repeat(50) { assertEquals(a.next(), b.next()) }
    }
}
