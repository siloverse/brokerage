package io.github.siloverse.brokerage.ledger.application.transfer.model

import java.util.UUID

data class TransferReceipt(
    val transferUuid: UUID,
    val replayed: Boolean,
)
