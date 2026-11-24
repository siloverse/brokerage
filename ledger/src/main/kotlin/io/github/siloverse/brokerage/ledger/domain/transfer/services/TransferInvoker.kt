package io.github.siloverse.brokerage.ledger.domain.transfer.services

import io.github.siloverse.brokerage.ledger.domain.account.command.Command
import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.LedgerEntryRepository

class TransferInvoker(
    private val ledgerEntryRepository: LedgerEntryRepository
) {

    fun invoke(transferId: Long, commands: List<Command>) {
        commands.forEach { ledgerEntryRepository.insert(transferId, it.accountId, it.entryAmount) }
        commands.sortedBy { it.accountId }.forEach { it.execute() }
    }
}