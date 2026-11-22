package io.github.siloverse.brokerage.ledger.application.withdrawal.model

import java.util.UUID

data class WithdrawalReceipt(
    val transferUuid: UUID,
    val replayed: Boolean,
)