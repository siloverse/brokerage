package io.github.siloverse.brokerage.ledger.domain.transfer.repositories

import io.github.siloverse.brokerage.ledger.domain.account.model.UnbalancedAsset

interface LedgerEntryRepository {
    fun insert(transferId: Long, accountId: Long, amount: Long)
    fun findUnbalancedAssets(transferId: Long): List<UnbalancedAsset>
}