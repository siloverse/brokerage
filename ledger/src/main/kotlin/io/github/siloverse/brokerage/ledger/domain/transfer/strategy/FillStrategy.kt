package io.github.siloverse.brokerage.ledger.domain.transfer.strategy

import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import io.github.siloverse.brokerage.ledger.domain.hold.model.UserHold
import io.github.siloverse.brokerage.ledger.domain.transfer.model.Fill

interface FillStrategy {
    val assetType: AssetType
    fun validate(fill: Fill, userHold: UserHold)
    fun book(fill: Fill, userHold: UserHold, transferId: Long)
}