package io.github.siloverse.brokerage.ledger.application.hold.model

import java.util.UUID

data class HoldReleaseReceipt(
    val holdUuid: UUID,
    val reference: UUID,
    val replayed: Boolean,
)