package io.github.siloverse.brokerage.orders.infrastructure.fix

import io.github.siloverse.brokerage.orders.application.venue.CancelOrder
import io.github.siloverse.brokerage.orders.domain.ClientOrderId
import io.github.siloverse.brokerage.orders.domain.ExecutionId
import io.github.siloverse.brokerage.orders.application.venue.ExecutionReport
import io.github.siloverse.brokerage.orders.application.venue.NewOrder
import io.github.siloverse.brokerage.orders.domain.Price
import io.github.siloverse.brokerage.orders.domain.Quantity
import io.github.siloverse.brokerage.orders.domain.SequenceNumber
import io.github.siloverse.brokerage.orders.domain.Side
import io.github.siloverse.brokerage.orders.domain.VenueOrderId
import io.github.siloverse.brokerage.orders.application.venue.VenueOrderStatus
import io.github.siloverse.brokerage.orders.application.venue.VenueOrderView
import io.github.siloverse.fix.message.FixField
import io.github.siloverse.fix.message.FixMessage
import io.github.siloverse.fix.order.ExecType
import io.github.siloverse.fix.order.FixOrdType
import io.github.siloverse.fix.order.FixSide
import io.github.siloverse.fix.order.OrdStatus
import io.github.siloverse.fix.order.OrderTag
import java.math.BigDecimal
import java.time.Instant

/** FIX tags in, port types out, and the reverse. Nothing else lives here. */
object FixMapping {

    fun newOrderBody(order: NewOrder, now: Instant): List<FixField> = listOf(
        FixField(OrderTag.CL_ORD_ID, order.clientOrderId.value),
        FixField(OrderTag.SYMBOL, order.instrument.symbol),
        FixField(OrderTag.SIDE, if (order.side == Side.BUY) FixSide.BUY else FixSide.SELL),
        FixField(OrderTag.TRANSACT_TIME, FixMessage.formatUtcTimestamp(now)),
        FixField(OrderTag.ORDER_QTY, order.quantity.shares.toString()),
        FixField(OrderTag.ORD_TYPE, FixOrdType.LIMIT),
        FixField(OrderTag.PRICE, formatPrice(order.limitPrice)),
        FixField(OrderTag.TIME_IN_FORCE, FixOrdType.DAY),
    )

    /** FIX wants a new ClOrdID on the cancel itself and the order's id in OrigClOrdID. */
    fun cancelBody(request: CancelOrder, cancelId: String, now: Instant): List<FixField> = listOfNotNull(
        FixField(OrderTag.ORIG_CL_ORD_ID, request.clientOrderId.value),
        FixField(OrderTag.CL_ORD_ID, cancelId),
        request.venueOrderId?.let { FixField(OrderTag.ORDER_ID, it.value) },
        FixField(OrderTag.TRANSACT_TIME, FixMessage.formatUtcTimestamp(now)),
    )

    fun statusRequestBody(id: ClientOrderId): List<FixField> = listOf(FixField(OrderTag.CL_ORD_ID, id.value))

    /** The parts of an ExecutionReport the broker always needs, whatever its ExecType. */
    class Common(val clientOrderId: ClientOrderId, val venueOrderId: VenueOrderId, val sequence: SequenceNumber, val venueTime: Instant, val status: OrdStatus, val cumQty: Quantity, val leavesQty: Quantity)

    fun common(message: FixMessage): Common = Common(
        clientOrderId = ClientOrderId(message.require(OrderTag.CL_ORD_ID)),
        venueOrderId = VenueOrderId(message.require(OrderTag.ORDER_ID)),
        sequence = SequenceNumber(message.seqNum),
        venueTime = message.getInstant(OrderTag.TRANSACT_TIME) ?: message.getInstant(io.github.siloverse.fix.message.Tag.SENDING_TIME) ?: Instant.EPOCH,
        status = OrdStatus.fromCode(message.require(OrderTag.ORD_STATUS)),
        cumQty = Quantity(message.getLong(OrderTag.CUM_QTY) ?: 0),
        leavesQty = Quantity(message.getLong(OrderTag.LEAVES_QTY) ?: 0),
    )

    /** An ExecutionReport (35=8) to a port report; `null` for ExecType=I, which is an answer, not a fact. */
    fun executionReport(message: FixMessage): ExecutionReport? {
        val c = common(message)
        return when (ExecType.fromCode(message.require(OrderTag.EXEC_TYPE))) {
            ExecType.NEW -> ExecutionReport.New(c.clientOrderId, c.venueOrderId, c.sequence, c.venueTime)
            ExecType.REJECTED -> ExecutionReport.Rejected(c.clientOrderId, c.venueOrderId, c.sequence, c.venueTime, message[OrderTag.TEXT] ?: "rejected")
            ExecType.TRADE -> ExecutionReport.Trade(
                c.clientOrderId, c.venueOrderId, c.sequence, c.venueTime,
                executionId = ExecutionId(message.require(OrderTag.EXEC_ID)),
                lastQty = Quantity(message.require(OrderTag.LAST_QTY).toLong()),
                lastPrice = parsePrice(message.require(OrderTag.LAST_PX)),
                cumQty = c.cumQty,
                leavesQty = c.leavesQty,
            )
            ExecType.CANCELED -> ExecutionReport.Canceled(c.clientOrderId, c.venueOrderId, c.sequence, c.venueTime, c.cumQty)
            ExecType.EXPIRED -> ExecutionReport.Expired(c.clientOrderId, c.venueOrderId, c.sequence, c.venueTime, c.cumQty)
            ExecType.ORDER_STATUS -> null
        }
    }

    /** An OrderCancelReject (35=9) to the port's CancelRejected. OrigClOrdID names the order. */
    fun cancelReject(message: FixMessage): ExecutionReport.CancelRejected = ExecutionReport.CancelRejected(
        clientOrderId = ClientOrderId(message.require(OrderTag.ORIG_CL_ORD_ID)),
        venueOrderId = VenueOrderId(message[OrderTag.ORDER_ID] ?: "NONE"),
        sequence = SequenceNumber(message.seqNum),
        venueTime = message.getInstant(OrderTag.TRANSACT_TIME) ?: Instant.EPOCH,
        reason = message[OrderTag.TEXT] ?: "cancel rejected",
    )

    /**
     * An ExecType=I report to the port's view. Executions are not carried by a status
     * report in FIX; the daily execution list (plan 2.7) fills that in. Empty here.
     */
    fun orderView(message: FixMessage): VenueOrderView {
        val c = common(message)
        return VenueOrderView(
            clientOrderId = c.clientOrderId,
            venueOrderId = c.venueOrderId,
            status = when (c.status) {
                OrdStatus.NEW -> VenueOrderStatus.NEW
                OrdStatus.PARTIALLY_FILLED -> VenueOrderStatus.PARTIALLY_FILLED
                OrdStatus.FILLED -> VenueOrderStatus.FILLED
                OrdStatus.CANCELED -> VenueOrderStatus.CANCELED
                OrdStatus.EXPIRED -> VenueOrderStatus.EXPIRED
                OrdStatus.REJECTED -> VenueOrderStatus.REJECTED
            },
            cumQty = c.cumQty,
            leavesQty = c.leavesQty,
            executions = emptyList(),
        )
    }

    fun formatPrice(price: Price): String = BigDecimal.valueOf(price.minorUnits).movePointLeft(2).toPlainString()
    fun parsePrice(text: String): Price = Price(BigDecimal(text).movePointRight(2).longValueExact())
}
