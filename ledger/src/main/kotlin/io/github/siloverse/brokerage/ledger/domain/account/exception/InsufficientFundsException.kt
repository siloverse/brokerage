package io.github.siloverse.brokerage.ledger.domain.account.exception

import java.util.UUID

class InsufficientFundsException(
    accountUuid: UUID,
    userUuid: UUID,
    amount: Long
) : RuntimeException(
    "User [$userUuid] with account [$accountUuid] has not enough available balance for amount [$amount]"
)