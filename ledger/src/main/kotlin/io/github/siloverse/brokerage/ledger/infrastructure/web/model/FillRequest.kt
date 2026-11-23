package io.github.siloverse.brokerage.ledger.infrastructure.web.model

import io.github.siloverse.brokerage.ledger.domain.account.model.enums.Currency
import java.util.UUID

data class FillRequest(
    val reference: UUID,
    val assetCode: String,
    val quantity: Long,
    val currency: Currency,
    val amount: Long,
    val fee: Long,
)
