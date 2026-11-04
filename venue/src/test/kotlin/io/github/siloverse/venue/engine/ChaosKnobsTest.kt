package io.github.siloverse.venue.engine

import io.github.siloverse.fix.message.FixField
import io.github.siloverse.fix.message.FixMessage
import io.github.siloverse.fix.order.ExecType
import io.github.siloverse.fix.order.FixOrdType
import io.github.siloverse.fix.order.FixSide
import io.github.siloverse.fix.order.OrderMsgType
import io.github.siloverse.fix.order.OrderTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

/** Each knob makes the engine misbehave in exactly the promised way, and in no other. */
class ChaosKnobsTest {

    private val t0 = Instant.parse("2026-11-04T10:00:00Z")
    private val pinned = EngineConfig(priceStep = 0, startPrice = 100_00, sessionClose = LocalTime.of(17, 30), zone = ZoneOffset.UTC)

    private fun newOrder(clOrdId: String) = FixMessage.create(OrderMsgType.NEW_ORDER_SINGLE, "BROKER", "VENUE", 1, t0, listOf(
        FixField(OrderTag.CL_ORD_ID, clOrdId), FixField(OrderTag.SYMBOL, "ACME"), FixField(OrderTag.SIDE, FixSide.BUY),
        FixField(OrderTag.ORDER_QTY, "10"), FixField(OrderTag.ORD_TYPE, FixOrdType.LIMIT), FixField(OrderTag.PRICE, "100.00"),
    ))

    private fun VenueReply.field(tag: Int) = body.firstOrNull { it.tag == tag }?.value
    private fun List<VenueReply>.execTypes() = map { ExecType.fromCode(it.field(OrderTag.EXEC_TYPE)!!) }

    @Test
    fun `rejectRate 1 rejects every order and records none`() {
        val engine = VenueEngine(pinned, ChaosKnobs(rejectRate = 1.0))

        val replies = engine.handle(newOrder("o1"), t0)

        assertEquals(listOf(ExecType.REJECTED), replies.execTypes())
        assertEquals("chaos: rejected on purpose", replies[0].field(OrderTag.TEXT))
        assertTrue(engine.orders.isEmpty())
    }

    @Test
    fun `duplicateReportRate 1 sends every trade twice with the same ExecID`() {
        val engine = VenueEngine(pinned, ChaosKnobs(duplicateReportRate = 1.0))

        val replies = engine.handle(newOrder("o1"), t0)

        assertEquals(listOf(ExecType.NEW, ExecType.TRADE, ExecType.TRADE), replies.execTypes())
        assertEquals(replies[1].field(OrderTag.EXEC_ID), replies[2].field(OrderTag.EXEC_ID))
        assertEquals(10L, engine.orders.single().cumQty, "the order itself was filled once")
    }

    @Test
    fun `phantomReportRate 1 sends a trade for a filled order on every tick, without changing the order`() {
        val engine = VenueEngine(pinned, ChaosKnobs(phantomReportRate = 1.0))
        engine.handle(newOrder("o1"), t0)

        val phantoms = engine.tick(t0.plusSeconds(1))

        assertEquals(listOf(ExecType.TRADE), phantoms.execTypes())
        assertTrue(phantoms[0].field(OrderTag.EXEC_ID)!!.startsWith("P-"))
        assertEquals("0", phantoms[0].field(OrderTag.LEAVES_QTY))
        assertEquals(10L, engine.orders.single().cumQty)
        assertEquals(1, engine.orders.single().executions.size)
    }

    @Test
    fun `knobs change behaviour of the next decision, not of past ones`() {
        val engine = VenueEngine(pinned)
        assertEquals(listOf(ExecType.NEW, ExecType.TRADE), engine.handle(newOrder("before"), t0).execTypes())

        engine.knobs.rejectRate = 1.0
        assertEquals(listOf(ExecType.REJECTED), engine.handle(newOrder("after"), t0).execTypes())

        engine.knobs.rejectRate = 0.0
        assertEquals(listOf(ExecType.NEW, ExecType.TRADE), engine.handle(newOrder("later"), t0).execTypes())
    }

    @Test
    fun `validate refuses rates outside 0 to 1 and partial fills outside 1 to 2`() {
        assertThrows(IllegalArgumentException::class.java) { ChaosKnobs(rejectRate = 1.5).validate() }
        assertThrows(IllegalArgumentException::class.java) { ChaosKnobs(partialFills = 3).validate() }
        ChaosKnobs().validate()
    }
}
