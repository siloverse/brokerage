package io.github.siloverse.brokerage.ledger.domain.account.command

interface Command {
    val accountId: Long
    val entryAmount: Long
    fun execute()
}