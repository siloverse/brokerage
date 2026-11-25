package io.github.siloverse.brokerage.ledger.domain.transfer.funding

data class Funding(
    val fromHold: Long = 0,
    val fromAvailable: Long = 0,
    val shortfall: Long = 0,
)