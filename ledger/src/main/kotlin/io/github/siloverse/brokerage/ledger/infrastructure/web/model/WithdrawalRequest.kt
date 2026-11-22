package io.github.siloverse.brokerage.ledger.infrastructure.web.model

import io.github.siloverse.brokerage.ledger.domain.account.model.enums.Currency
import java.util.UUID

data class WithdrawalRequest(
    val userUuid: UUID,
    val currency: Currency,
    val amount: Long,
)
