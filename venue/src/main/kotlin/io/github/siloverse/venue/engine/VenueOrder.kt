package io.github.siloverse.venue.engine

import io.github.siloverse.fix.order.OrdStatus
import java.time.Instant

/** One order as the venue knows it. Mutable on purpose: the engine is single-threaded and owns it. */
class VenueOrder(
    val orderId: String,
    val clOrdId: String,
    val symbol: String,
    val buy: Boolean,
    val quantity: Long,
    val limitPrice: Long,
    val receivedAt: Instant,
) {
    var status: OrdStatus = OrdStatus.NEW
    var cumQty: Long = 0
    val executions = mutableListOf<Execution>()

    /** Set when the order first crosses; fills happen at or after it. */
    var fillDueAt: Instant? = null

    val leavesQty: Long get() = if (status.isTerminal) 0 else quantity - cumQty

    fun crosses(mid: Long): Boolean = if (buy) limitPrice >= mid else limitPrice <= mid

    class Execution(val execId: String, val quantity: Long, val price: Long, val at: Instant)
}
