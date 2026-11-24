package io.github.siloverse.brokerage.ledger.domain.account.command

import io.github.siloverse.brokerage.ledger.domain.account.exception.InsufficientFundsException
import io.github.siloverse.brokerage.ledger.domain.account.repositories.AccountRepository
import java.util.UUID

class TakeFromAvailableCommand(
    private val receiver: AccountRepository,
    override val accountId: Long,
    private val amount: Long,
    private val accountUuid: UUID,
    private val userUuid: UUID,
) : Command {
    override val entryAmount: Long = -amount
    override fun execute() {
        if (receiver.takeFromAvailable(accountId, amount) == 0) {
            throw InsufficientFundsException(accountUuid = accountUuid, userUuid = userUuid, amount = amount)
        }
    }
}