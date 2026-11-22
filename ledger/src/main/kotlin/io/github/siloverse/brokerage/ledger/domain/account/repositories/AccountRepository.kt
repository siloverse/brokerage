package io.github.siloverse.brokerage.ledger.domain.account.repositories

import io.github.siloverse.brokerage.ledger.domain.account.model.AccountIdentifier
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.Currency
import java.util.UUID

interface AccountRepository {
    fun ensureUserAccount(userUuid: UUID, assetType: AssetType, currency: Currency): AccountIdentifier
    fun ensureSettlementAccount(assetType: AssetType, currency: Currency): AccountIdentifier
    fun addToBooked(accountId: Long, amount: Long)
    fun takeFromAvailable(accountId: Long, amount: Long): Int
}