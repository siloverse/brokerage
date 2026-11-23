package io.github.siloverse.brokerage.ledger.infrastructure.web.model

import java.util.UUID

data class FillRequest(
    val reference: UUID,
    val assetCode: String,
    val quantity: Long,
    val amount: Long,
    val fee: Long,
)
