package io.github.siloverse.brokerage.ledger.application.deposit.model

import java.util.UUID

data class DepositReceipt(
    val transferUuid: UUID,
    val replayed: Boolean,
)