package io.github.siloverse.brokerage.orders.application

import io.github.siloverse.brokerage.orders.domain.Price
import io.github.siloverse.brokerage.orders.domain.Instrument
import java.time.Instant

data class TopOfBook(
    val instrument: Instrument,
    val bid: Price?,
    val ask: Price?,
    val venueTime: Instant,
)
