package io.github.siloverse.brokerage.orders

/** The venue's authoritative view of one order. FIX OrdStatus (39) for the status. */
enum class VenueOrderStatus { NEW, PARTIALLY_FILLED, FILLED, CANCELED, EXPIRED, REJECTED }

data class VenueOrderView(
    val clientOrderId: ClientOrderId,
    val venueOrderId: VenueOrderId,
    val status: VenueOrderStatus,
    val cumQty: Quantity,
    val leavesQty: Quantity,
    val executions: List<ExecutionReport.Trade>,
)
