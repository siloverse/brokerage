package io.github.siloverse.brokerage.ledger.domain.hold.exception

import java.util.UUID

class HoldFillException(
    message: String,
    holdUuid: UUID,
    amount: Long,
) : RuntimeException("$message {holdUuid:[$holdUuid], requestedAmount:[$amount]}")