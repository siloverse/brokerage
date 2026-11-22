package io.github.siloverse.brokerage.ledger.application.hold.exception

import java.util.UUID

class HoldNotFoundException(
    reference: UUID,
) : RuntimeException(
    "Hold doesn't exist for reference [$reference]"
)