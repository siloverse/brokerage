package io.github.siloverse.brokerage.orders.venue.fix

import io.github.siloverse.brokerage.orders.CancelOrder
import io.github.siloverse.brokerage.orders.ClientOrderId
import io.github.siloverse.brokerage.orders.ExecutionReport
import io.github.siloverse.brokerage.orders.Instrument
import io.github.siloverse.brokerage.orders.NewOrder
import io.github.siloverse.brokerage.orders.Price
import io.github.siloverse.brokerage.orders.Quantity
import io.github.siloverse.brokerage.orders.Side
import io.github.siloverse.brokerage.orders.VenueOrderId
import io.github.siloverse.brokerage.orders.VenueOrderStatus
import io.github.siloverse.fix.message.FixField
import io.github.siloverse.fix.message.FixMessage
import io.github.siloverse.fix.order.OrderTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class FixMappingTest {

    private val now = Instant.parse("2026-11-04T10:00:00Z")

    private fun report(vararg fields: Pair<Int, String>) =
        FixMessage.create("8", "VENUE", "BROKER", 4711, now, fields.map { FixField(it.first, it.second) })

    @Test
    fun `a new order becomes a NewOrderSingle body with FIX codes and a decimal price`() {
        val body = FixMapping.newOrderBody(NewOrder(ClientOrderId("c1"), Instrument("ACME"), Side.SELL, Price(12_34), Quantity(7)), now)
        val byTag = body.associate { it.tag to it.value }

        assertEquals("c1", byTag[OrderTag.CL_ORD_ID])
        assertEquals("ACME", byTag[OrderTag.SYMBOL])
        assertEquals("2", byTag[OrderTag.SIDE])
        assertEquals("7", byTag[OrderTag.ORDER_QTY])
        assertEquals("2", byTag[OrderTag.ORD_TYPE])
        assertEquals("12.34", byTag[OrderTag.PRICE])
        assertEquals("0", byTag[OrderTag.TIME_IN_FORCE])
    }

    @Test
    fun `a cancel carries the order's id as OrigClOrdID and its own new ClOrdID`() {
        val body = FixMapping.cancelBody(CancelOrder(ClientOrderId("c1"), VenueOrderId("V-9")), "cxl-1", now).associate { it.tag to it.value }

        assertEquals("c1", body[OrderTag.ORIG_CL_ORD_ID])
        assertEquals("cxl-1", body[OrderTag.CL_ORD_ID])
        assertEquals("V-9", body[OrderTag.ORDER_ID])
    }

    @Test
    fun `a trade report maps to Trade with the session sequence number`() {
        val trade = FixMapping.executionReport(report(
            OrderTag.ORDER_ID to "V-1", OrderTag.CL_ORD_ID to "c1", OrderTag.EXEC_ID to "E-1", OrderTag.EXEC_TYPE to "F",
            OrderTag.ORD_STATUS to "1", OrderTag.LEAVES_QTY to "5", OrderTag.CUM_QTY to "5",
            OrderTag.LAST_QTY to "5", OrderTag.LAST_PX to "100.25", OrderTag.TRANSACT_TIME to "20261104-10:00:00.000",
        )) as ExecutionReport.Trade

        assertEquals("E-1", trade.executionId.value)
        assertEquals(4711L, trade.sequence.value)
        assertEquals(Price(100_25), trade.lastPrice)
        assertEquals(Quantity(5), trade.lastQty)
        assertEquals(Quantity(5), trade.leavesQty)
        assertEquals(now, trade.venueTime)
    }

    @Test
    fun `every ExecType maps to its report, and status maps to none`() {
        fun of(execType: String, status: String) = FixMapping.executionReport(report(
            OrderTag.ORDER_ID to "V-1", OrderTag.CL_ORD_ID to "c1", OrderTag.EXEC_ID to "S-1", OrderTag.EXEC_TYPE to execType,
            OrderTag.ORD_STATUS to status, OrderTag.LEAVES_QTY to "0", OrderTag.CUM_QTY to "10", OrderTag.TEXT to "why",
        ))

        assertTrue(of("0", "0") is ExecutionReport.New)
        assertTrue(of("8", "8") is ExecutionReport.Rejected)
        assertTrue(of("4", "4") is ExecutionReport.Canceled)
        assertTrue(of("C", "C") is ExecutionReport.Expired)
        assertNull(of("I", "2"))
        assertEquals("why", (of("8", "8") as ExecutionReport.Rejected).reason)
    }

    @Test
    fun `a status answer maps to a view without executions`() {
        val view = FixMapping.orderView(report(
            OrderTag.ORDER_ID to "V-1", OrderTag.CL_ORD_ID to "c1", OrderTag.EXEC_ID to "S-1", OrderTag.EXEC_TYPE to "I",
            OrderTag.ORD_STATUS to "2", OrderTag.LEAVES_QTY to "0", OrderTag.CUM_QTY to "10",
        ))

        assertEquals(VenueOrderStatus.FILLED, view.status)
        assertEquals(Quantity(10), view.cumQty)
        assertTrue(view.executions.isEmpty())
    }

    @Test
    fun `a cancel reject names the order by OrigClOrdID`() {
        val rejected = FixMapping.cancelReject(FixMessage.create("9", "VENUE", "BROKER", 12, now, listOf(
            FixField(OrderTag.ORDER_ID, "V-1"), FixField(OrderTag.CL_ORD_ID, "cxl-1"), FixField(OrderTag.ORIG_CL_ORD_ID, "c1"),
            FixField(OrderTag.ORD_STATUS, "2"), FixField(OrderTag.TEXT, "order already filled"),
        )))

        assertEquals("c1", rejected.clientOrderId.value)
        assertEquals("order already filled", rejected.reason)
        assertEquals(12L, rejected.sequence.value)
    }
}
