package io.github.siloverse.brokerage.ledger.domain.transfer.funding

abstract class FundingHandler(private val successor: FundingHandler? = null) {

    fun fund(wanted: Long, funding: Funding = Funding()): Funding {
        require(wanted >= 0) { "wanted must not be negative" }

        val taken = take(wanted)
        val updated = record(funding, taken)
        val rest = wanted - taken

        if (rest == 0L) return updated
        checkNotNull(successor) { "the end of the chain was reached with $rest still unfunded" }
        return successor.fund(rest, updated)
    }

    protected abstract fun take(wanted: Long): Long

    protected abstract fun record(funding: Funding, taken: Long): Funding
}