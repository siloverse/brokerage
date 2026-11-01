package io.github.siloverse.brokerage.orders

import java.time.Instant

data class TopOfBook(
    val instrument: Instrument,
    val bid: Price?,
    val ask: Price?,
    val venueTime: Instant,
)
