package io.github.siloverse.brokerage.ledger.application.withdrawal.exception

import java.util.UUID

class InsufficientFundsException(
    accountUuid: UUID,
    userUuid: UUID,
    amount: Long
) : RuntimeException(
    "User [$userUuid] with account [$accountUuid] has not sufficient balance to withdraw amount [$amount]"
)