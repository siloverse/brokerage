package io.github.siloverse.brokerage.ledger.application.check.services

import io.github.siloverse.brokerage.ledger.domain.check.model.LedgerCheckReport
import io.github.siloverse.brokerage.ledger.domain.check.repositories.LedgerCheckRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class LedgerCheckService(private val ledgerCheckRepository: LedgerCheckRepository) {

    @Transactional(transactionManager = "ledgerTransactionManager", readOnly = true)
    fun check(): LedgerCheckReport = LedgerCheckReport(
        unbalancedTransfers = ledgerCheckRepository.findUnbalancedTransfers(),
        bookedMismatches = ledgerCheckRepository.findBookedMismatches(),
        reservedMismatches = ledgerCheckRepository.findReservedMismatches(),
    )
}
