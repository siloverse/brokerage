package io.github.siloverse.brokerage.ledger.domain.hold.model

import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import java.util.UUID

data class UserHold(
    val holdId: Long,
    val holdUuid: UUID,
    val accountId: Long,
    val userUuid: UUID,
    val assetCode: String,
    val assetType: AssetType
)