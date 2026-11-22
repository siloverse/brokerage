package io.github.siloverse.brokerage.ledger.application.hold.model

import io.github.siloverse.brokerage.ledger.domain.account.model.enums.Currency
import java.util.UUID

data class HoldCommand(
    val reference: UUID,
    val userUuid: UUID,
    val currency: Currency,
    val amount: Long,
) {
    init {
        require(amount > 0) { "amount must be positive" }
    }
}
