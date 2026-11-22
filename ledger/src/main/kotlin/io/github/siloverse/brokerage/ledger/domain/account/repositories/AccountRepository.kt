package io.github.siloverse.brokerage.ledger.domain.account.repositories

import io.github.siloverse.brokerage.ledger.domain.account.model.AssetType
import io.github.siloverse.brokerage.ledger.domain.account.model.Currency
import java.util.UUID

interface AccountRepository {
    fun ensureUserAccount(userUuid: UUID, assetType: AssetType, currency: Currency): Long
    fun ensureSettlementAccount(assetType: AssetType, currency: Currency): Long
    fun addToBooked(accountId: Long, amount: Long)
}