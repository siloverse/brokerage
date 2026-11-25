package io.github.siloverse.brokerage.ledger.domain.account.model

import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType

data class UnbalancedAsset(val assetType: AssetType, val assetCode: String, val sum: Long)