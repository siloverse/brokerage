package io.github.siloverse.brokerage.ledger.domain.check.model

data class LedgerCheckReport(
    val unbalancedTransfers: List<UnbalancedTransfer>,
    val bookedMismatches: List<BookedMismatch>,
    val reservedMismatches: List<ReservedMismatch>,
) {
    val healthy: Boolean = unbalancedTransfers.isEmpty() && bookedMismatches.isEmpty() && reservedMismatches.isEmpty()
}
