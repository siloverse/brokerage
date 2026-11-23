package io.github.siloverse.brokerage.ledger.domain.hold.model

import java.util.UUID

data class UserHold(
    val holdId: Long,
    val holdUuid: UUID,
    val accountId: Long,
    val userUuid: UUID,
    val assetCode: String
)