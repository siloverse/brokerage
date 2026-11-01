package io.github.siloverse.brokerage.orders

import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The contract every [Venue] implementation must pass. Subclass it next to the
 * implementation and provide a fresh venue per test.
 */
abstract class VenueContract {

    protected abstract fun venue(): Venue

    protected open val instrument = Instrument("ACME")

    private fun order(
        id: ClientOrderId = ClientOrderId(UUID.randomUUID().toString()),
        qty: Long = 10,
    ) = NewOrder(id, instrument, Side.BUY, Price(10_00), Quantity(qty))

    @Test
    fun `guarantee 1 - submit is idempotent on client order id`() = runTest {
        val venue = venue()
        val order = order()

        val first = venue.submit(order)
        val second = venue.submit(order)

        assertEquals(first, second)
        val view = venue.lookup(order.clientOrderId)
        assertEquals(first, view?.venueOrderId)
    }

    @Test
    fun `guarantee 3 - reports for one order carry increasing sequence numbers`() = runTest {
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
    fun `guarantee 5 - lookup of an unknown id is null`() = runTest {
        assertNull(venue().lookup(ClientOrderId("never-submitted")))
    }

    @Test
    fun `guarantee 6 - a cancel of a done order is rejected, not honoured`() = runTest {
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