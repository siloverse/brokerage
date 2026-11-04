package io.github.siloverse.brokerage.orders.application

import io.github.siloverse.brokerage.orders.domain.Price
import io.github.siloverse.brokerage.orders.domain.Quantity
import io.github.siloverse.brokerage.orders.domain.Side
import io.github.siloverse.brokerage.orders.domain.TimeInForce
import io.github.siloverse.brokerage.orders.domain.ClientOrderId
import io.github.siloverse.brokerage.orders.domain.VenueOrderId
import io.github.siloverse.brokerage.orders.domain.Instrument

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
