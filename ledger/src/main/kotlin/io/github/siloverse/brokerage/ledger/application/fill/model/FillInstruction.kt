package io.github.siloverse.brokerage.ledger.application.fill.model

import io.github.siloverse.brokerage.ledger.domain.account.model.enums.Currency
import java.util.UUID

data class FillInstruction(
    val executionUuid: UUID,
    val reference: UUID,
    val assetCode: String,
    val quantity: Long,
    val currency: Currency,
    val amount: Long,
    val fee: Long,
) {
    init {
        require(assetCode.isNotBlank()) { "instrument must not be blank" }
        require(quantity > 0) { "quantity must be positive" }
        require(amount > 0) { "amount must be positive" }
        require(fee >= 0) { "fee must not be negative" }
        require(fee < amount) { "must not be more than" }
    }
}
