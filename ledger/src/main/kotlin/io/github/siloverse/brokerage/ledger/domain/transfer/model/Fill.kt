package io.github.siloverse.brokerage.ledger.domain.transfer.model

import io.github.siloverse.brokerage.ledger.domain.account.model.enums.Currency

data class Fill(
    val assetCode: String,
    val quantity: Long,
    val currency: Currency,
    val amount: Long,
    val fee: Long,
)