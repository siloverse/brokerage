package io.github.siloverse.brokerage.ledger.domain.transfer.repositories

interface LedgerEntryRepository {
    fun insert(transferId: Long, accountId: Long, amount: Long)
}