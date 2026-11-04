package io.github.siloverse.venue.engine

import io.github.siloverse.fix.message.FixField
import io.github.siloverse.fix.message.FixMessage
import io.github.siloverse.fix.order.ExecType
import io.github.siloverse.fix.order.FixOrdType
import io.github.siloverse.fix.order.FixSide
import io.github.siloverse.fix.order.OrdStatus
import io.github.siloverse.fix.order.OrderMsgType
import io.github.siloverse.fix.order.OrderTag
import java.time.Instant
import java.time.ZonedDateTime

/**
 * The fake exchange, as a pure state machine: order messages and clock ticks in, replies
 * out. No threads, no sockets, no Spring. [VenueFixServer] drives it.
 *
 * Behaviour (plan 0.3b): New on receipt; a DAY limit order whose price crosses the random
 * walk mid fills at the mid after [EngineConfig.fillDelay], in one or two executions;
 * otherwise it rests and is checked on every tick; resting orders expire at the session
 * close; a cancel of a live order is honoured, of a done order rejected; a duplicate
 * ClOrdID gets the same OrderID back and no second order; an OrderStatusRequest is
 * answered with ExecType=I.
 */
class VenueEngine(private val config: EngineConfig) {

    val prices = PriceFeed(config.startPrice, config.priceStep, config.seed)
    private val byClOrdId = LinkedHashMap<String, VenueOrder>()
    private val byOrderId = HashMap<String, VenueOrder>()
    private var orderCounter = 0L
    private var execCounter = 0L
    private var reportCounter = 0L

    val orders: Collection<VenueOrder> get() = byClOrdId.values

    /** Route one inbound application message. Unknown types are answered with nothing. */
    fun handle(message: FixMessage, now: Instant): List<VenueReply> = when (message.msgType) {
        OrderMsgType.NEW_ORDER_SINGLE -> newOrder(message, now)
        OrderMsgType.ORDER_CANCEL_REQUEST -> cancel(message, now)
        OrderMsgType.ORDER_STATUS_REQUEST -> status(message, now)
        else -> emptyList()
    }

    /** Time passed: move prices, fill what crosses and is due, expire what rests past the close. */
    fun tick(now: Instant): List<VenueReply> {
        prices.tick()
        val replies = mutableListOf<VenueReply>()
        val closeNow = now >= sessionCloseOn(now)
        for (order in byClOrdId.values) {
            if (order.status.isTerminal) continue
            if (closeNow) {
                order.status = OrdStatus.EXPIRED
                replies += report(order, ExecType.EXPIRED, now)
                continue
            }
            val mid = prices.mid(order.symbol)
            if (order.fillDueAt == null && order.crosses(mid)) order.fillDueAt = now.plus(config.fillDelay)
            val due = order.fillDueAt ?: continue
            if (now < due) continue
            if (!order.crosses(mid)) { order.fillDueAt = null; continue }   // price ran away during the delay
            replies += fill(order, mid, now)
        }
        return replies
    }

    // ---- messages ---------------------------------------------------------------------------

    private fun newOrder(message: FixMessage, now: Instant): List<VenueReply> {
        val clOrdId = message[OrderTag.CL_ORD_ID] ?: return listOf(rejectNew(message, "missing ClOrdID", now))
        byClOrdId[clOrdId]?.let { existing ->
            // Guarantee 1: a retry gets the same OrderID and creates nothing.
            return listOf(report(existing, ExecType.ORDER_STATUS, now))
        }
        val symbol = message[OrderTag.SYMBOL] ?: return listOf(rejectNew(message, "missing Symbol", now))
        val side = message[OrderTag.SIDE] ?: return listOf(rejectNew(message, "missing Side", now))
        val quantity = message.getLong(OrderTag.ORDER_QTY) ?: return listOf(rejectNew(message, "missing OrderQty", now))
        val price = message[OrderTag.PRICE]?.let(::parsePrice) ?: return listOf(rejectNew(message, "missing Price", now))
        if (message[OrderTag.ORD_TYPE] != FixOrdType.LIMIT) return listOf(rejectNew(message, "only limit orders", now))
        if ((message[OrderTag.TIME_IN_FORCE] ?: FixOrdType.DAY) != FixOrdType.DAY) return listOf(rejectNew(message, "only DAY orders", now))
        if (quantity <= 0 || price <= 0) return listOf(rejectNew(message, "quantity and price must be positive", now))
        if (now >= sessionCloseOn(now)) return listOf(rejectNew(message, "session closed", now))

        val order = VenueOrder("V-${++orderCounter}", clOrdId, symbol, side == FixSide.BUY, quantity, price, now)
        byClOrdId[clOrdId] = order
        byOrderId[order.orderId] = order
        val replies = mutableListOf(report(order, ExecType.NEW, now))
        // An immediately crossing order with no delay fills in the same breath: the "fill before accept" ordering is real.
        if (order.crosses(prices.mid(symbol))) {
            order.fillDueAt = now.plus(config.fillDelay)
            if (config.fillDelay.isZero) replies += fill(order, prices.mid(symbol), now)
        }
        return replies
    }

    private fun cancel(message: FixMessage, now: Instant): List<VenueReply> {
        val clOrdId = message[OrderTag.CL_ORD_ID] ?: return emptyList()
        val target = message[OrderTag.ORIG_CL_ORD_ID]?.let { byClOrdId[it] }
            ?: message[OrderTag.ORDER_ID]?.let { byOrderId[it] }
            ?: byClOrdId[clOrdId]
            ?: return listOf(cancelReject(clOrdId, message[OrderTag.ORIG_CL_ORD_ID] ?: clOrdId, null, "unknown order", now))
        if (target.status.isTerminal) {
            return listOf(cancelReject(clOrdId, target.clOrdId, target.orderId, "order already ${target.status.name.lowercase()}", now))
        }
        target.status = OrdStatus.CANCELED
        return listOf(report(target, ExecType.CANCELED, now))
    }

    private fun status(message: FixMessage, now: Instant): List<VenueReply> {
        val clOrdId = message[OrderTag.CL_ORD_ID] ?: return emptyList()
        val order = byClOrdId[clOrdId] ?: byOrderId[message[OrderTag.ORDER_ID] ?: ""]
            ?: return listOf(unknownOrderStatus(clOrdId, now))
        return listOf(report(order, ExecType.ORDER_STATUS, now))
    }

    // ---- fills and reports ----------------------------------------------------------------------

    private fun fill(order: VenueOrder, mid: Long, now: Instant): List<VenueReply> {
        val remaining = order.leavesQty
        val quantity = if (config.partialFills == 2 && order.executions.isEmpty() && remaining > 1) remaining / 2 else remaining
        val execution = VenueOrder.Execution("E-${++execCounter}", quantity, mid, now)
        order.executions += execution
        order.cumQty += quantity
        order.status = if (order.cumQty >= order.quantity) OrdStatus.FILLED else OrdStatus.PARTIALLY_FILLED
        if (order.status == OrdStatus.PARTIALLY_FILLED) order.fillDueAt = now.plus(config.fillDelay)
        return listOf(report(order, ExecType.TRADE, now, execution))
    }

    private fun report(order: VenueOrder, execType: ExecType, now: Instant, execution: VenueOrder.Execution? = null): VenueReply =
        VenueReply(
            OrderMsgType.EXECUTION_REPORT,
            listOfNotNull(
                FixField(OrderTag.ORDER_ID, order.orderId),
                FixField(OrderTag.CL_ORD_ID, order.clOrdId),
                FixField(OrderTag.EXEC_ID, execution?.execId ?: "S-${++reportCounter}"),
                FixField(OrderTag.EXEC_TYPE, execType.code),
                FixField(OrderTag.ORD_STATUS, order.status.code),
                FixField(OrderTag.SYMBOL, order.symbol),
                FixField(OrderTag.SIDE, if (order.buy) FixSide.BUY else FixSide.SELL),
                FixField(OrderTag.ORDER_QTY, order.quantity.toString()),
                FixField(OrderTag.PRICE, formatPrice(order.limitPrice)),
                FixField(OrderTag.LEAVES_QTY, order.leavesQty.toString()),
                FixField(OrderTag.CUM_QTY, order.cumQty.toString()),
                execution?.let { FixField(OrderTag.LAST_QTY, it.quantity.toString()) },
                execution?.let { FixField(OrderTag.LAST_PX, formatPrice(it.price)) },
                FixField(OrderTag.TRANSACT_TIME, FixMessage.formatUtcTimestamp(now)),
            ),
        )

    private fun rejectNew(message: FixMessage, reason: String, now: Instant): VenueReply =
        VenueReply(
            OrderMsgType.EXECUTION_REPORT,
            listOf(
                FixField(OrderTag.ORDER_ID, "NONE"),
                FixField(OrderTag.CL_ORD_ID, message[OrderTag.CL_ORD_ID] ?: "NONE"),
                FixField(OrderTag.EXEC_ID, "S-${++reportCounter}"),
                FixField(OrderTag.EXEC_TYPE, ExecType.REJECTED.code),
                FixField(OrderTag.ORD_STATUS, OrdStatus.REJECTED.code),
                FixField(OrderTag.SYMBOL, message[OrderTag.SYMBOL] ?: "NONE"),
                FixField(OrderTag.SIDE, message[OrderTag.SIDE] ?: FixSide.BUY),
                FixField(OrderTag.LEAVES_QTY, "0"),
                FixField(OrderTag.CUM_QTY, "0"),
                FixField(OrderTag.TEXT, reason),
                FixField(OrderTag.TRANSACT_TIME, FixMessage.formatUtcTimestamp(now)),
            ),
        )

    private fun cancelReject(clOrdId: String, origClOrdId: String, orderId: String?, reason: String, now: Instant): VenueReply =
        VenueReply(
            OrderMsgType.ORDER_CANCEL_REJECT,
            listOf(
                FixField(OrderTag.ORDER_ID, orderId ?: "NONE"),
                FixField(OrderTag.CL_ORD_ID, clOrdId),
                FixField(OrderTag.ORIG_CL_ORD_ID, origClOrdId),
                FixField(OrderTag.ORD_STATUS, (orderId?.let { byOrderId[it]?.status } ?: OrdStatus.REJECTED).code),
                FixField(OrderTag.CXL_REJ_RESPONSE_TO, "1"),
                FixField(OrderTag.CXL_REJ_REASON, if (orderId == null) "1" else "0"),   // 1 = unknown order, 0 = too late
                FixField(OrderTag.TEXT, reason),
                FixField(OrderTag.TRANSACT_TIME, FixMessage.formatUtcTimestamp(now)),
            ),
        )

    private fun unknownOrderStatus(clOrdId: String, now: Instant): VenueReply =
        VenueReply(
            OrderMsgType.EXECUTION_REPORT,
            listOf(
                FixField(OrderTag.ORDER_ID, "NONE"),
                FixField(OrderTag.CL_ORD_ID, clOrdId),
                FixField(OrderTag.EXEC_ID, "S-${++reportCounter}"),
                FixField(OrderTag.EXEC_TYPE, ExecType.ORDER_STATUS.code),
                FixField(OrderTag.ORD_STATUS, OrdStatus.REJECTED.code),
                FixField(OrderTag.ORD_REJ_REASON, "5"),   // 5 = unknown order
                FixField(OrderTag.LEAVES_QTY, "0"),
                FixField(OrderTag.CUM_QTY, "0"),
                FixField(OrderTag.TEXT, UNKNOWN_ORDER),
                FixField(OrderTag.TRANSACT_TIME, FixMessage.formatUtcTimestamp(now)),
            ),
        )

    private fun sessionCloseOn(now: Instant): Instant =
        ZonedDateTime.ofInstant(now, config.zone).with(config.sessionClose).toInstant()

    companion object {
        const val UNKNOWN_ORDER = "unknown order"

        /** Prices travel as decimals with two places: "10.00" is 1000 minor units. */
        fun parsePrice(text: String): Long = java.math.BigDecimal(text).movePointRight(2).longValueExact()
        fun formatPrice(minorUnits: Long): String = java.math.BigDecimal.valueOf(minorUnits).movePointLeft(2).toPlainString()
    }
}
