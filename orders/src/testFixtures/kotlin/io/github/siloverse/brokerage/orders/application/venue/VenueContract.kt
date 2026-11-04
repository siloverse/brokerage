package io.github.siloverse.brokerage.orders.application.venue

import io.github.siloverse.brokerage.orders.domain.Price
import io.github.siloverse.brokerage.orders.domain.Quantity
import io.github.siloverse.brokerage.orders.domain.Side
import io.github.siloverse.brokerage.orders.domain.ClientOrderId
import io.github.siloverse.brokerage.orders.domain.Instrument
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The contract every [Venue] implementation must pass. Subclass it next to the
 * implementation and provide a fresh venue per test.
 *
 * Runs under `runBlocking`, not `runTest`: implementations are backed by real threads and
 * sockets, and the virtual clock of `runTest` would fire every timeout before a real
 * answer could arrive.
 */
abstract class VenueContract {

    protected abstract fun venue(): Venue

    protected open val instrument = Instrument("ACME")

    /**
     * Make the venue under test deliver every Trade report twice. Return false when the
     * implementation cannot be told to (the in-process engine, for one); guarantees 2 and 4
     * are then skipped, not failed.
     */
    protected open fun induceDuplicateReports(): Boolean = false

    private fun order(
        id: ClientOrderId = ClientOrderId(UUID.randomUUID().toString()),
        qty: Long = 10,
    ) = NewOrder(id, instrument, Side.BUY, Price(10_00), Quantity(qty))

    @Test
    fun `guarantee 1 - submit is idempotent on client order id`() = runBlocking {
        val venue = venue()
        val order = order()

        val first = venue.submit(order)
        val second = venue.submit(order)

        assertEquals(first, second)
        val view = venue.lookup(order.clientOrderId)
        assertEquals(first, view?.venueOrderId)
    }

    @Test
    fun `guarantee 3 - reports for one order carry increasing sequence numbers`() = runBlocking {
        val venue = venue()
        val order = order()
        venue.submit(order)

        val reports = venue.executions().filter {
            it.clientOrderId == order.clientOrderId
        }.take(2)
            .toList()

        val sequences = reports.map { it.sequence.value }
        assertEquals(sequences.sorted(), sequences)
        assertEquals(sequences.distinct(), sequences)
    }

    @Test
    fun `guarantees 2 and 4 - a report delivered twice carries the same execution id and content, under new sequence numbers`() = runBlocking {
        org.junit.jupiter.api.Assumptions.assumeTrue(induceDuplicateReports(), "this venue cannot be told to duplicate")
        val venue = venue()
        val order = order()
        venue.submit(order)

        val trades = withTimeout(5_000) {
            venue.executions()
                .filter { it.clientOrderId == order.clientOrderId && it is ExecutionReport.Trade }
                .map { it as ExecutionReport.Trade }
                .take(2)
                .toList()
        }

        // Guarantee 2: the venue's id for the execution never changes, so the broker can dedupe on it.
        assertEquals(trades[0].executionId, trades[1].executionId)
        assertEquals(trades[0].copy(sequence = trades[1].sequence, venueTime = trades[1].venueTime), trades[1])
        // Guarantee 4: it really was delivered twice, as two session messages.
        assertTrue(trades[1].sequence.value > trades[0].sequence.value)
    }

    @Test
    fun `guarantee 5 - lookup of an unknown id is null`() = runBlocking {
        assertNull(venue().lookup(ClientOrderId("never-submitted")))
    }

    @Test
    fun `guarantee 6 - a cancel of a done order is rejected, not honoured`() = runBlocking {
        val venue = venue()
        val order = order()
        val venueOrderId = venue.submit(order)

        venue.executions().first {
            it.clientOrderId == order.clientOrderId && it is ExecutionReport.Trade && it.leavesQty.shares == 0L
        }

        venue.cancel(CancelOrder(order.clientOrderId, venueOrderId))

        val answer = venue.executions().first {
            it.clientOrderId == order.clientOrderId && it is ExecutionReport.CancelRejected
        }

        assertTrue(answer is ExecutionReport.CancelRejected)
    }
}
