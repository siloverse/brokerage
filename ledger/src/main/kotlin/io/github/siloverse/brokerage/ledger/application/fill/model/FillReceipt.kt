package io.github.siloverse.brokerage.ledger.application.fill.model

import java.util.UUID

data class FillReceipt(
    val transferUuid: UUID,
    val replayed: Boolean,
)
