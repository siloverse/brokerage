package io.github.siloverse.brokerage.ledger.domain.account.command

import io.github.siloverse.brokerage.ledger.domain.account.repositories.AccountRepository

class SettleReservedCommand(
    private val receiver: AccountRepository,
    override val accountId: Long,
    private val amount: Long,
) : Command {
    override val entryAmount: Long = -amount
    override fun execute() { receiver.settleReserved(accountId, amount) }
}