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
        val updates = holdRepository.consumeHold(
            id = userHold.holdId,
            amount = fill.quantity
        )

        if (updates == 0) {
            throw HoldFillException(
                message = "Unable to consume the Hold to sell shares position as too low consumable hold shares are left",
                holdUuid = userHold.holdUuid,
                amount = fill.quantity
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
            assetCode = fill.currency.name,
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
            assetCode = fill.currency.name,
        )

        val feeIncomeAccountIdentifier = accountRepository.ensureAccount(
            accountType = AccountType.FEE_INCOME,
            assetType = AssetType.CASH,
            assetCode = fill.currency.name,
        )

        val commands = buildList {
            add(SettleReservedCommand(accountRepository, userPositionAccountIdentifier.id, fill.quantity))
            add(AddToBookedCommand(accountRepository, venuePositionAccountIdentifier.id, fill.quantity))
            add(RemoveFromBookedCommand(accountRepository, settlementCashAccountIdentifier.id, fill.amount))
            add(AddToBookedCommand(accountRepository, userCashAccountIdentifier.id, fill.amount))
            if (fill.fee != 0L) {
                add(RemoveFromBookedCommand(accountRepository, userCashAccountIdentifier.id, fill.fee))
                add(AddToBookedCommand(accountRepository, feeIncomeAccountIdentifier.id, fill.fee))
            }
        }
        transferInvoker.invoke(transferId, commands)
    }
}