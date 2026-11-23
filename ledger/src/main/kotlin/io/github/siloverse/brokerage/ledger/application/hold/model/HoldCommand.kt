package io.github.siloverse.brokerage.ledger.application.hold.model

import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.Currency
import java.util.UUID

data class HoldCommand(
    val reference: UUID,
    val userUuid: UUID,
    val assetType: AssetType,
    val assetCode: String,
    val amount: Long,
) {
    init {
        require(amount > 0) { "amount must be positive" }
        require(assetType != AssetType.CASH || Currency.entries.any { it.name == assetCode }) { "unknown currency" }
        require(assetCode.isNotBlank()) { "Asset code is not provided" }
    }
}
