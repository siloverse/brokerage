package io.github.siloverse.brokerage.ledger.domain.check.model

import java.util.UUID

data class ReservedMismatch(val accountUuid: UUID, val reserved: Long, val openHolds: Long)
