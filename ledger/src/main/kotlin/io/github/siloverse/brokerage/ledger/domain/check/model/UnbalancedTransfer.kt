package io.github.siloverse.brokerage.ledger.domain.check.model

import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import java.util.UUID

data class UnbalancedTransfer(val transferUuid: UUID, val assetType: AssetType, val assetCode: String, val sum: Long)
