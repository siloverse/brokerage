package io.github.siloverse.brokerage.ledger.domain.account.model

import java.util.UUID


data class AccountIdentifier(
    val id: Long,
    val uuid: UUID
)