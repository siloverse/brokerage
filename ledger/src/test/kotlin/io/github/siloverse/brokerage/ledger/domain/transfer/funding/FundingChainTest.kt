package io.github.siloverse.brokerage.ledger.domain.transfer.funding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FundingChainTest {

    private fun chain(holdRest: Long, available: Long) =
        HoldFundingHandler(holdRest, AvailableFundingHandler(available, ReceivableFundingHandler()))

    @Test
    fun `a hold that covers everything pays everything`() {
        assertEquals(Funding(fromHold = 20_200), chain(holdRest = 50_500, available = 49_500).fund(20_200))
    }

    @Test
    fun `a hold can be used to the last cent`() {
        assertEquals(Funding(fromHold = 50_500), chain(holdRest = 50_500, available = 0).fund(50_500))
    }

    @Test
    fun `what the hold cannot cover comes from the free balance`() {
        assertEquals(
            Funding(fromHold = 5_000, fromAvailable = 15_200),
            chain(holdRest = 5_000, available = 49_500).fund(20_200)
        )
    }

    @Test
    fun `a released hold pays nothing, the free balance pays what it has, the rest is a shortfall`() {
        assertEquals(
            Funding(fromHold = 0, fromAvailable = 10_000, shortfall = 10_200),
            chain(holdRest = 0, available = 10_000).fund(20_200)
        )
    }

    @Test
    fun `all three sources can be needed for one fill`() {
        assertEquals(
            Funding(fromHold = 5_000, fromAvailable = 10_000, shortfall = 5_200),
            chain(holdRest = 5_000, available = 10_000).fund(20_200)
        )
    }

    @Test
    fun `with nothing in the hold and nothing free the whole amount is a shortfall`() {
        assertEquals(Funding(shortfall = 20_200), chain(holdRest = 0, available = 0).fund(20_200))
    }

    @Test
    fun `the three parts always add up to what was wanted`() {
        for (holdRest in listOf(0L, 1, 99, 100, 101, 500)) {
            for (available in listOf(0L, 1, 99, 100, 101, 500)) {
                for (wanted in listOf(0L, 1, 100, 250, 1_000)) {
                    val funding = chain(holdRest, available).fund(wanted)
                    assertEquals(wanted, funding.fromHold + funding.fromAvailable + funding.shortfall)
                    assertEquals(true, funding.fromHold <= holdRest && funding.fromAvailable <= available)
                }
            }
        }
    }

    @Test
    fun `a source is never asked before the one in front of it is used up`() {
        val funding = chain(holdRest = 100, available = 100).fund(150)

        assertEquals(Funding(fromHold = 100, fromAvailable = 50), funding)
    }

    @Test
    fun `wanting nothing takes nothing`() {
        assertEquals(Funding(), chain(holdRest = 100, available = 100).fund(0))
    }

    @Test
    fun `a negative rest or balance counts as nothing`() {
        assertEquals(Funding(shortfall = 100), chain(holdRest = -5, available = -7).fund(100))
    }

    @Test
    fun `a negative amount is refused`() {
        assertThrows<IllegalArgumentException> { chain(holdRest = 100, available = 100).fund(-1) }
    }

    @Test
    fun `a chain that ends before everything is funded fails loudly`() {
        val endsTooEarly = object : FundingHandler() {
            override fun take(wanted: Long) = 0L
            override fun record(funding: Funding, taken: Long) = funding
        }

        assertThrows<IllegalStateException> { endsTooEarly.fund(100) }
    }
}
