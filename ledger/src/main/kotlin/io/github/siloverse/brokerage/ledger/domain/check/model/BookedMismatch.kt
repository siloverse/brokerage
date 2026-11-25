package io.github.siloverse.brokerage.ledger.domain.check.model

import java.util.UUID

data class BookedMismatch(val accountUuid: UUID, val booked: Long, val entriesSum: Long)
