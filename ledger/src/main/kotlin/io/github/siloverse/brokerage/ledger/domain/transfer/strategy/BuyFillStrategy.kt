package io.github.siloverse.brokerage.ledger.domain.transfer.strategy

import io.github.siloverse.brokerage.ledger.domain.account.command.AddToBookedCommand
import io.github.siloverse.brokerage.ledger.domain.account.command.RemoveFromBookedCommand
import io.github.siloverse.brokerage.ledger.domain.account.command.SettleReservedCommand
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AccountType
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import io.github.siloverse.brokerage.ledger.domain.account.repositories.AccountRepository
import io.github.siloverse.brokerage.ledger.domain.hold.exception.HoldFillException
import io.github.siloverse.brokerage.ledger.domain.hold.model.UserHold
import io.github.siloverse.brokerage.ledger.domain.hold.repositories.HoldRepository
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

    override fun book(fill: Fill, userHold: UserHold, transferId: Long){
        val updates = holdRepository.consumeHold(
            id = userHold.holdId,
            amount = fill.amount + fill.fee
        )

        if (updates == 0) {
            throw HoldFillException(
                message = "Unable to consume the Hold to fill position as too low consumable hold amount is left",
                holdUuid = userHold.holdUuid,
                amount = fill.amount + fill.fee
            )
        }

        val userPositionAccountIdentifier = accountRepository.ensureAccount(
            accountType = AccountType.USER,
            assetType = AssetType.POSITION,
            assetCode = fill.assetCode,
            userUuid = userHold.userUuid
        )

        val userCashAccountIdentifier = accountRepository.ensureAccount(
            accountType = AccountType.USER,
            assetType = AssetType.CASH,
            assetCode = userHold.assetCode,
            userUuid = userHold.userUuid
        )

        val venuePositionAccountIdentifier = accountRepository.ensureAccount(
            accountType = AccountType.VENUE_SETTLEMENT,
            assetType = AssetType.POSITION,
            assetCode = fill.assetCode,
        )

        val settlementCashAccountIdentifier = accountRepository.ensureAccount(
            accountType = AccountType.VENUE_SETTLEMENT,
            assetType = AssetType.CASH,
            assetCode = userHold.assetCode,
        )

        val feeIncomeAccountIdentifier = accountRepository.ensureAccount(
            accountType = AccountType.FEE_INCOME,
            assetType = AssetType.CASH,
            assetCode = userHold.assetCode,
        )

        val commands = buildList {
            add(SettleReservedCommand(accountRepository, userCashAccountIdentifier.id, fill.amount))
            add(AddToBookedCommand(accountRepository, settlementCashAccountIdentifier.id, fill.amount))
            add(RemoveFromBookedCommand(accountRepository, venuePositionAccountIdentifier.id, fill.quantity))
            add(AddToBookedCommand(accountRepository, userPositionAccountIdentifier.id, fill.quantity))
            if (fill.fee != 0L) {
                add(SettleReservedCommand(accountRepository, userCashAccountIdentifier.id, fill.fee))
                add(AddToBookedCommand(accountRepository, feeIncomeAccountIdentifier.id, fill.fee))
            }
        }
        transferInvoker.invoke(transferId, commands)
    }
}