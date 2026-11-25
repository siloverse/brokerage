package io.github.siloverse.brokerage.ledger.domain.transfer.funding

class HoldFundingHandler(
    private val holdRest: Long,
    successor: FundingHandler,
) : FundingHandler(successor) {

    override fun take(wanted: Long): Long = minOf(wanted, holdRest.coerceAtLeast(0))

    override fun record(funding: Funding, taken: Long): Funding = funding.copy(fromHold = taken)
}