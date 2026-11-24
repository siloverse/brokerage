package io.github.siloverse.brokerage.ledger.application.deposit.model

import io.github.siloverse.brokerage.ledger.domain.account.model.enums.Currency
import java.util.UUID

data class DepositInstruction(
    val idempotencyKey: UUID,
    val userUuid: UUID,
    val currency: Currency,
    val amount: Long,
) {
    init {
        require(amount > 0) { "amount must be positive" }
    }
}
