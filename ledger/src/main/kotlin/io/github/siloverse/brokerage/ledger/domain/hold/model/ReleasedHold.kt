package io.github.siloverse.brokerage.ledger.domain.hold.model

import java.util.UUID

data class ReleasedHold(
    val id: Long,
    val uuid: UUID,
    val reference: UUID,
    val accountId: Long,
    val released: Long,
)