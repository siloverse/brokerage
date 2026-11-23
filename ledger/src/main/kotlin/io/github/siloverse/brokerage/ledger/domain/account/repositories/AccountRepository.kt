package io.github.siloverse.brokerage.ledger.domain.account.repositories

import io.github.siloverse.brokerage.ledger.domain.account.model.AccountIdentifier
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AccountType
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.Currency
import java.util.UUID

interface AccountRepository {
    fun ensureUserAccount(userUuid: UUID, assetType: AssetType, currency: Currency): AccountIdentifier
    fun ensureSettlementAccount(assetType: AssetType, currency: Currency): AccountIdentifier
    fun ensureAccount(
        accountType: AccountType,
        assetType: AssetType,
        assetCode: String,
        userUuid: UUID? = null
    ): AccountIdentifier

    fun addToBooked(accountId: Long, amount: Long)
    fun removeFromBooked(accountId: Long, amount: Long)
    fun takeFromAvailable(accountId: Long, amount: Long): Int
    fun reserve(accountId: Long, amount: Long): Int
    fun release(accountId: Long, amount: Long): Int
    fun settleReserved(accountId: Long, amount: Long): Int
}