package io.github.siloverse.venue.engine

import io.github.siloverse.fix.message.FixField
import io.github.siloverse.fix.message.FixMessage
import io.github.siloverse.fix.order.ExecType
import io.github.siloverse.fix.order.FixOrdType
import io.github.siloverse.fix.order.FixSide
import io.github.siloverse.fix.order.OrdStatus
import io.github.siloverse.fix.order.OrderMsgType
import io.github.siloverse.fix.order.OrderTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

/** The fake exchange as a pure state machine: messages and ticks in, replies out. */
class VenueEngineTest {

    private val t0 = Instant.parse("2026-11-04T10:00:00Z")
    private val pinned = EngineConfig(priceStep = 0, startPrice = 100_00, sessionClose = LocalTime.of(17, 30), zone = ZoneOffset.UTC)

    private fun newOrder(clOrdId: String, side: String = FixSide.BUY, qty: Long = 10, price: String = "100.00", symbol: String = "ACME") =
        FixMessage.create(OrderMsgType.NEW_ORDER_SINGLE, "BROKER", "VENUE", 1, t0, listOf(
            FixField(OrderTag.CL_ORD_ID, clOrdId), FixField(OrderTag.SYMBOL, symbol), FixField(OrderTag.SIDE, side),
            FixField(OrderTag.ORDER_QTY, qty.toString()), FixField(OrderTag.ORD_TYPE, FixOrdType.LIMIT),
            FixField(OrderTag.PRICE, price), FixField(OrderTag.TIME_IN_FORCE, FixOrdType.DAY),
        ))

    private fun cancel(clOrdId: String, origClOrdId: String) =
        FixMessage.create(OrderMsgType.ORDER_CANCEL_REQUEST, "BROKER", "VENUE", 1, t0, listOf(
            FixField(OrderTag.CL_ORD_ID, clOrdId), FixField(OrderTag.ORIG_CL_ORD_ID, origClOrdId),
        ))

    private fun status(clOrdId: String) =
        FixMessage.create(OrderMsgType.ORDER_STATUS_REQUEST, "BROKER", "VENUE", 1, t0, listOf(FixField(OrderTag.CL_ORD_ID, clOrdId)))

    private fun List<VenueReply>.execTypes() = map { reply -> reply.body.first { it.tag == OrderTag.EXEC_TYPE }.value.let(ExecType::fromCode) }
    private fun VenueReply.field(tag: Int) = body.firstOrNull { it.tag == tag }?.value

    @Test
    fun `a crossing order with no delay is accepted and filled in one go`() {
        val engine = VenueEngine(pinned)

        val replies = engine.handle(newOrder("o1", price = "100.00"), t0)

        assertEquals(listOf(ExecType.NEW, ExecType.TRADE), replies.execTypes())
        val trade = replies[1]
        assertEquals("10", trade.field(OrderTag.LAST_QTY))
        assertEquals("100.00", trade.field(OrderTag.LAST_PX))
        assertEquals("0", trade.field(OrderTag.LEAVES_QTY))
        assertEquals(OrdStatus.FILLED.code, trade.field(OrderTag.ORD_STATUS))
        assertEquals("V-1", trade.field(OrderTag.ORDER_ID))
        assertEquals("E-1", trade.field(OrderTag.EXEC_ID))
    }

    @Test
    fun `a non-crossing order rests and fills when the price comes to it`() {
        val engine = VenueEngine(pinned)
        assertEquals(listOf(ExecType.NEW), engine.handle(newOrder("o1", price = "99.00"), t0).execTypes())
        assertTrue(engine.tick(t0.plusSeconds(1)).isEmpty())

        engine.prices.set("ACME", 99_00)

        assertEquals(listOf(ExecType.TRADE), engine.tick(t0.plusSeconds(2)).execTypes())
    }

    @Test
    fun `fill delay holds the fill until it is due`() {
        val engine = VenueEngine(pinned.copy(fillDelay = Duration.ofSeconds(2)))
        assertEquals(listOf(ExecType.NEW), engine.handle(newOrder("o1"), t0).execTypes())

        assertTrue(engine.tick(t0.plusSeconds(1)).isEmpty())
        assertEquals(listOf(ExecType.TRADE), engine.tick(t0.plusSeconds(2)).execTypes())
    }

    @Test
    fun `two partial fills add up and the second one carries the final status`() {
        val engine = VenueEngine(pinned.copy(partialFills = 2))

        val first = engine.handle(newOrder("o1", qty = 10), t0)
        assertEquals(listOf(ExecType.NEW, ExecType.TRADE), first.execTypes())
        assertEquals("5", first[1].field(OrderTag.LAST_QTY))
        assertEquals("5", first[1].field(OrderTag.LEAVES_QTY))
        assertEquals(OrdStatus.PARTIALLY_FILLED.code, first[1].field(OrderTag.ORD_STATUS))

        val second = engine.tick(t0.plusMillis(1))
        assertEquals(listOf(ExecType.TRADE), second.execTypes())
        assertEquals("5", second[0].field(OrderTag.LAST_QTY))
        assertEquals("10", second[0].field(OrderTag.CUM_QTY))
        assertEquals(OrdStatus.FILLED.code, second[0].field(OrderTag.ORD_STATUS))
        assertTrue(second[0].field(OrderTag.EXEC_ID) != first[1].field(OrderTag.EXEC_ID))
    }

    @Test
    fun `a duplicate ClOrdID gets the same OrderID back and creates no second order`() {
        val engine = VenueEngine(pinned)
        val first = engine.handle(newOrder("o1"), t0)

        val again = engine.handle(newOrder("o1"), t0.plusSeconds(1))

        assertEquals(listOf(ExecType.ORDER_STATUS), again.execTypes())
        assertEquals(first[0].field(OrderTag.ORDER_ID), again[0].field(OrderTag.ORDER_ID))
        assertEquals(1, engine.orders.size)
    }

    @Test
    fun `cancel of a resting order is honoured, of a filled order rejected, of an unknown order rejected`() {
        val engine = VenueEngine(pinned)
        engine.handle(newOrder("rest", price = "1.00"), t0)
        engine.handle(newOrder("done"), t0)

        val cancelled = engine.handle(cancel("c1", "rest"), t0).single()
        assertEquals(OrderMsgType.EXECUTION_REPORT, cancelled.msgType)
        assertEquals(ExecType.CANCELED.code, cancelled.field(OrderTag.EXEC_TYPE))
        assertEquals("0", cancelled.field(OrderTag.CUM_QTY))
        assertEquals("0", cancelled.field(OrderTag.LEAVES_QTY))

        val tooLate = engine.handle(cancel("c2", "done"), t0).single()
        assertEquals(OrderMsgType.ORDER_CANCEL_REJECT, tooLate.msgType)
        assertEquals("0", tooLate.field(OrderTag.CXL_REJ_REASON))
        assertEquals("V-2", tooLate.field(OrderTag.ORDER_ID))

        val unknown = engine.handle(cancel("c3", "nope"), t0).single()
        assertEquals(OrderMsgType.ORDER_CANCEL_REJECT, unknown.msgType)
        assertEquals("1", unknown.field(OrderTag.CXL_REJ_REASON))
    }

    @Test
    fun `status request answers with ExecType I, and unknown orders are flagged as such`() {
        val engine = VenueEngine(pinned.copy(partialFills = 2))
        engine.handle(newOrder("o1", qty = 10), t0)

        val known = engine.handle(status("o1"), t0).single()
        assertEquals(ExecType.ORDER_STATUS.code, known.field(OrderTag.EXEC_TYPE))
        assertEquals(OrdStatus.PARTIALLY_FILLED.code, known.field(OrderTag.ORD_STATUS))
        assertEquals("5", known.field(OrderTag.CUM_QTY))
        assertEquals("5", known.field(OrderTag.LEAVES_QTY))

        val unknown = engine.handle(status("ghost"), t0).single()
        assertEquals(ExecType.ORDER_STATUS.code, unknown.field(OrderTag.EXEC_TYPE))
        assertEquals(OrdStatus.REJECTED.code, unknown.field(OrderTag.ORD_STATUS))
        assertEquals(VenueEngine.UNKNOWN_ORDER, unknown.field(OrderTag.TEXT))
    }

    @Test
    fun `resting orders expire at the session close and new orders after it are rejected`() {
        val engine = VenueEngine(pinned)
        engine.handle(newOrder("rest", price = "1.00"), t0)
        val close = Instant.parse("2026-11-04T17:30:00Z")

        assertTrue(engine.tick(close.minusSeconds(1)).isEmpty())
        val expired = engine.tick(close).single()
        assertEquals(ExecType.EXPIRED.code, expired.field(OrderTag.EXEC_TYPE))
        assertEquals("0", expired.field(OrderTag.LEAVES_QTY))

        val late = engine.handle(newOrder("late"), close.plusSeconds(1)).single()
        assertEquals(ExecType.REJECTED.code, late.field(OrderTag.EXEC_TYPE))
        assertEquals("session closed", late.field(OrderTag.TEXT))
    }

    @Test
    fun `a malformed order is rejected, not ignored`() {
        val engine = VenueEngine(pinned)
        val noPrice = FixMessage.create(OrderMsgType.NEW_ORDER_SINGLE, "BROKER", "VENUE", 1, t0, listOf(
            FixField(OrderTag.CL_ORD_ID, "bad"), FixField(OrderTag.SYMBOL, "ACME"), FixField(OrderTag.SIDE, FixSide.BUY),
            FixField(OrderTag.ORDER_QTY, "10"), FixField(OrderTag.ORD_TYPE, FixOrdType.LIMIT),
        ))

        val reply = engine.handle(noPrice, t0).single()

        assertEquals(ExecType.REJECTED.code, reply.field(OrderTag.EXEC_TYPE))
        assertEquals("missing Price", reply.field(OrderTag.TEXT))
        assertTrue(engine.orders.isEmpty())
    }

    @Test
    fun `the random walk moves prices and a pinned walk does not`() {
        val walking = VenueEngine(EngineConfig(priceStep = 5, startPrice = 100_00, seed = 7))
        walking.prices.mid("ACME")
        repeat(50) { walking.prices.tick() }
        assertTrue(walking.prices.mid("ACME") != 100_00L)

        val still = VenueEngine(pinned)
        still.prices.mid("ACME")
        repeat(50) { still.prices.tick() }
        assertEquals(100_00L, still.prices.mid("ACME"))
    }
}
