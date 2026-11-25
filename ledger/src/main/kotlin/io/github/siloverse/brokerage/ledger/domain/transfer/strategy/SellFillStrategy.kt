package io.github.siloverse.brokerage.ledger.domain.transfer.strategy

import io.github.siloverse.brokerage.ledger.domain.account.command.AddToBookedCommand
import io.github.siloverse.brokerage.ledger.domain.account.command.RemoveFromBookedCommand
import io.github.siloverse.brokerage.ledger.domain.account.command.SettleReservedCommand
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AccountType
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import io.github.siloverse.brokerage.ledger.domain.account.repositories.AccountRepository
import io.github.siloverse.brokerage.ledger.domain.hold.model.UserHold
import io.github.siloverse.brokerage.ledger.domain.hold.repositories.HoldRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.funding.AvailableFundingHandler
import io.github.siloverse.brokerage.ledger.domain.transfer.funding.HoldFundingHandler
import io.github.siloverse.brokerage.ledger.domain.transfer.funding.ReceivableFundingHandler
import io.github.siloverse.brokerage.ledger.domain.transfer.model.Fill
import io.github.siloverse.brokerage.ledger.domain.transfer.services.TransferInvoker

class SellFillStrategy(
    private val holdRepository: HoldRepository,
    private val accountRepository: AccountRepository,
    private val transferInvoker: TransferInvoker,
) : FillStrategy {

    override val assetType = AssetType.POSITION

    override fun validate(fill: Fill, userHold: UserHold) {
        require(userHold.assetCode == fill.assetCode) { "the hold is on another instrument" }
    }

    override fun book(fill: Fill, userHold: UserHold, transferId: Long) {
        val instrument = userHold.assetCode
        val currency = fill.currency.name
        val userPositionId = userHold.accountId

        // 1. lock: the hold row first, then the account rows in ascending id
        val holdRest = holdRepository.lockRest(userHold.holdId)

        val userCash = accountRepository.ensureAccount(AccountType.USER, AssetType.CASH, currency, userHold.userUuid)
        val venuePosition =
            accountRepository.ensureAccount(AccountType.VENUE_SETTLEMENT, AssetType.POSITION, instrument)
        val settlementCash = accountRepository.ensureAccount(AccountType.VENUE_SETTLEMENT, AssetType.CASH, currency)
        val feeIncome = accountRepository.ensureAccount(AccountType.FEE_INCOME, AssetType.CASH, currency)

        val available = accountRepository
            .lockAvailable(listOf(userPositionId, userCash.id, venuePosition.id, settlementCash.id, feeIncome.id))
            .getValue(userPositionId)

        // 2. decide: where the shares come from
        val funding = HoldFundingHandler(
            holdRest,
            AvailableFundingHandler(available, ReceivableFundingHandler())
        ).fund(fill.quantity)

        // 3. write
        if (funding.fromHold > 0) {
            check(
                holdRepository.consumeHold(
                    userHold.holdId,
                    funding.fromHold
                ) == 1
            ) { "the locked hold could not be consumed" }
        }

        val commands = buildList {
            if (funding.fromHold > 0) {
                add(SettleReservedCommand(accountRepository, userPositionId, funding.fromHold))
            }
            if (funding.fromAvailable > 0) {
                add(RemoveFromBookedCommand(accountRepository, userPositionId, funding.fromAvailable))
            }
            if (funding.shortfall > 0) {
                val receivable = accountRepository.ensureAccount(
                    AccountType.CUSTOMER_RECEIVABLE, AssetType.POSITION, instrument, userHold.userUuid
                )
                add(RemoveFromBookedCommand(accountRepository, receivable.id, funding.shortfall))
            }
            add(AddToBookedCommand(accountRepository, venuePosition.id, fill.quantity))
            add(RemoveFromBookedCommand(accountRepository, settlementCash.id, fill.amount))
            add(AddToBookedCommand(accountRepository, userCash.id, fill.amount))
            if (fill.fee > 0) {
                add(RemoveFromBookedCommand(accountRepository, userCash.id, fill.fee))
                add(AddToBookedCommand(accountRepository, feeIncome.id, fill.fee))
            }
        }
        transferInvoker.invoke(transferId, commands)
    }
}