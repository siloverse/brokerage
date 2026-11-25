package io.github.siloverse.brokerage.ledger.domain.check.repositories

import io.github.siloverse.brokerage.ledger.domain.check.model.BookedMismatch
import io.github.siloverse.brokerage.ledger.domain.check.model.ReservedMismatch
import io.github.siloverse.brokerage.ledger.domain.check.model.UnbalancedTransfer

interface LedgerCheckRepository {
    fun findUnbalancedTransfers(): List<UnbalancedTransfer>
    fun findBookedMismatches(): List<BookedMismatch>
    fun findReservedMismatches(): List<ReservedMismatch>
}
