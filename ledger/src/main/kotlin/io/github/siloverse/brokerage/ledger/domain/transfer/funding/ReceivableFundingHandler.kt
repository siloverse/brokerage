package io.github.siloverse.brokerage.ledger.domain.transfer.funding

class ReceivableFundingHandler : FundingHandler() {

    override fun take(wanted: Long): Long = wanted

    override fun record(funding: Funding, taken: Long): Funding = funding.copy(shortfall = taken)
}