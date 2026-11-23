package io.github.siloverse.brokerage.ledger.infrastructure.web.model

import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import java.util.UUID

data class HoldRequest(
    val reference: UUID,
    val userUuid: UUID,
    val assetType: AssetType,
    val assetCode: String,
    val amount: Long,
)
