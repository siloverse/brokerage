package io.github.siloverse.brokerage.orders

data class NewOrder(
    val clientOrderId: ClientOrderId,
    val instrument: Instrument,
    val side: Side,
    val limitPrice: Price,
    val quantity: Quantity,
    val timeInForce: TimeInForce = TimeInForce.DAY,
)

data class CancelOrder(
    val clientOrderId: ClientOrderId,
    /** Known once the submit receipt arrived; null when cancelling an order still in flight. */
    val venueOrderId: VenueOrderId?,
)
