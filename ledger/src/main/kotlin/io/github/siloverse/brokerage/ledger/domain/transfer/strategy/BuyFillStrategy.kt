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

class BuyFillStrategy(
    private val holdRepository: HoldRepository,
    private val accountRepository: AccountRepository,
    private val transferInvoker: TransferInvoker,
) : FillStrategy {

    override val assetType = AssetType.CASH


    override fun validate(fill: Fill, userHold: UserHold) {
        require(userHold.assetCode == fill.currency.name) { "the hold is in another currency" }
    }

    override fun book(fill: Fill, userHold: UserHold, transferId: Long) {

        val total = fill.amount + fill.fee
        val currency = userHold.assetCode
        val userCashId = userHold.accountId

        val holdRest = holdRepository.lockRest(userHold.holdId)


        val userPosition = accountRepository.ensureAccount(
            accountType = AccountType.USER,
            assetType = AssetType.POSITION,
            assetCode = fill.assetCode,
            userUuid = userHold.userUuid
        )

        val venuePosition = accountRepository.ensureAccount(
            accountType = AccountType.VENUE_SETTLEMENT,
            assetType = AssetType.POSITION,
            assetCode = fill.assetCode,
        )

        val settlementCash = accountRepository.ensureAccount(
            accountType = AccountType.VENUE_SETTLEMENT,
            assetType = AssetType.CASH,
            assetCode = userHold.assetCode,
        )

        val feeIncome = accountRepository.ensureAccount(
            accountType = AccountType.FEE_INCOME,
            assetType = AssetType.CASH,
            assetCode = userHold.assetCode,
        )

        val available = accountRepository
            .lockAvailable(listOf(userCashId, userPosition.id, venuePosition.id, settlementCash.id, feeIncome.id))
            .getValue(userCashId)

        val funding = HoldFundingHandler(
            holdRest = holdRest,
            successor = AvailableFundingHandler(
                available = available,
                successor = ReceivableFundingHandler()
            )
        ).fund(total)

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
                add(SettleReservedCommand(accountRepository, userCashId, funding.fromHold))
            }
            if (funding.fromAvailable > 0) {
                add(RemoveFromBookedCommand(accountRepository, userCashId, funding.fromAvailable))
            }
            if (funding.shortfall > 0) {
                val receivable = accountRepository.ensureAccount(
                    AccountType.CUSTOMER_RECEIVABLE, AssetType.CASH, currency, userHold.userUuid
                )
                add(RemoveFromBookedCommand(accountRepository, receivable.id, funding.shortfall))
            }
            add(AddToBookedCommand(accountRepository, settlementCash.id, fill.amount))
            if (fill.fee > 0) {
                add(AddToBookedCommand(accountRepository, feeIncome.id, fill.fee))
            }
            add(RemoveFromBookedCommand(accountRepository, venuePosition.id, fill.quantity))
            add(AddToBookedCommand(accountRepository, userPosition.id, fill.quantity))
        }
        transferInvoker.invoke(transferId, commands)
    }
}