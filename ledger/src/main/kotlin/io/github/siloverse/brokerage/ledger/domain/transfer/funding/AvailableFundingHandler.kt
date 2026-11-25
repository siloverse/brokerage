package io.github.siloverse.brokerage.ledger.domain.transfer.funding

class AvailableFundingHandler(
    private val available: Long,
    successor: FundingHandler,
) : FundingHandler(successor) {

    override fun take(wanted: Long): Long = minOf(wanted, available.coerceAtLeast(0))

    override fun record(funding: Funding, taken: Long): Funding = funding.copy(fromAvailable = taken)
}