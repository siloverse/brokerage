package io.github.siloverse.brokerage.ledger.application.hold.model

import java.util.UUID

data class HoldReceipt(
    val holdUuid: UUID,
    val replayed: Boolean,
)