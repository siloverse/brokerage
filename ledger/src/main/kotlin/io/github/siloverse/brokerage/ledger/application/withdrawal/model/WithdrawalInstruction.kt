package io.github.siloverse.brokerage.ledger.application.withdrawal.model

import io.github.siloverse.brokerage.ledger.domain.account.model.enums.Currency
import java.util.*

data class WithdrawalInstruction(
    val idempotencyKey: UUID,
    val userUuid: UUID,
    val amount: Long,
    val currency: Currency
){
    init {
        require(amount > 0) { "amount must be positive" }
    }
}