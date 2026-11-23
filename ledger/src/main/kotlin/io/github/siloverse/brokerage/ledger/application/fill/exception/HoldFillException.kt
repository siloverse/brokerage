package io.github.siloverse.brokerage.ledger.application.fill.exception

import java.util.UUID

class HoldFillException(
    message: String,
    holdUuid: UUID,
    executionUuid: UUID,
    reference: UUID,
    amount: Long
) : RuntimeException(
    "$message {holdUuid:[$holdUuid], executionUuid:[$executionUuid], reference:[$reference], requestedAmount:[$amount]}"
)