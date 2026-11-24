package io.github.siloverse.brokerage.ledger.application.fill.services

import io.github.siloverse.brokerage.ledger.application.fill.exception.HoldFillException
import io.github.siloverse.brokerage.ledger.application.fill.model.FillInstruction
import io.github.siloverse.brokerage.ledger.application.fill.model.FillReceipt
import io.github.siloverse.brokerage.ledger.application.hold.exception.HoldNotFoundException
import io.github.siloverse.brokerage.ledger.domain.account.command.AddToBookedCommand
import io.github.siloverse.brokerage.ledger.domain.account.command.RemoveFromBookedCommand
import io.github.siloverse.brokerage.ledger.domain.account.command.SettleReservedCommand
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AccountType
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import io.github.siloverse.brokerage.ledger.domain.account.repositories.AccountRepository
import io.github.siloverse.brokerage.ledger.domain.hold.model.UserHold
import io.github.siloverse.brokerage.ledger.domain.hold.repositories.HoldRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.model.TransferKind
import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.TransferRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.services.TransferInvoker
import io.github.siloverse.identifiers.core.UuidV7
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class FillService(
    private val holdRepository: HoldRepository,
    private val transferRepository: TransferRepository,
    private val accountRepository: AccountRepository,
    private val transferInvoker: TransferInvoker
) {
    @Transactional(transactionManager = "ledgerTransactionManager")
    fun fill(fillInstruction: FillInstruction): FillReceipt {
        val userHold = holdRepository.findUserHoldByReference(fillInstruction.reference) ?: throw HoldNotFoundException(
            reference = fillInstruction.reference
        )
        when (userHold.assetType) {
            AssetType.CASH -> require(userHold.assetCode == fillInstruction.currency.name) { "the hold is in another currency" }
            AssetType.POSITION -> require(userHold.assetCode == fillInstruction.assetCode) { "the hold is on another instrument" }
        }

        val transferUuid = UuidV7.next()
        val transferId = transferRepository.insert(
            uuid = transferUuid,
            kind = TransferKind.FILL,
            idempotencyKey = fillInstruction.executionUuid,
            holdId = userHold.holdId
        ) ?: return FillReceipt(
            transferUuid = transferRepository.findUuidByKey(fillInstruction.executionUuid),
            replayed = true
        )

        when (userHold.assetType) {
            AssetType.CASH -> bookBuy(fillInstruction, userHold, transferId)
            AssetType.POSITION -> bookSell(fillInstruction, userHold, transferId)
        }

        return FillReceipt(
            transferUuid = transferUuid,
            replayed = false
        )
    }

    private fun bookBuy(fillInstruction: FillInstruction, userHold: UserHold, transferId: Long) {
        val updates = holdRepository.consumeHold(
            id = userHold.holdId,
            amount = fillInstruction.amount + fillInstruction.fee
        )

        if (updates == 0) {
            throw HoldFillException(
                message = "Unable to consume the Hold to fill position as too low consumable hold amount is left",
                holdUuid = userHold.holdUuid,
                executionUuid = fillInstruction.executionUuid,
                reference = fillInstruction.reference,
                amount = fillInstruction.amount + fillInstruction.fee
            )
        }

        val userPositionAccountIdentifier = accountRepository.ensureAccount(
            accountType = AccountType.USER,
            assetType = AssetType.POSITION,
            assetCode = fillInstruction.assetCode,
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
            assetCode = fillInstruction.assetCode,
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
            add(SettleReservedCommand(accountRepository, userCashAccountIdentifier.id, fillInstruction.amount))
            add(AddToBookedCommand(accountRepository, settlementCashAccountIdentifier.id, fillInstruction.amount))
            add(RemoveFromBookedCommand(accountRepository, venuePositionAccountIdentifier.id, fillInstruction.quantity))
            add(AddToBookedCommand(accountRepository, userPositionAccountIdentifier.id, fillInstruction.quantity))
            if (fillInstruction.fee != 0L) {
                add(SettleReservedCommand(accountRepository, userCashAccountIdentifier.id, fillInstruction.fee))
                add(AddToBookedCommand(accountRepository, feeIncomeAccountIdentifier.id, fillInstruction.fee))
            }
        }
        transferInvoker.invoke(transferId, commands)
    }

    private fun bookSell(fillInstruction: FillInstruction, userHold: UserHold, transferId: Long) {
        val updates = holdRepository.consumeHold(
            id = userHold.holdId,
            amount = fillInstruction.quantity
        )

        if (updates == 0) {
            throw HoldFillException(
                message = "Unable to consume the Hold to sell shares position as too low consumable hold shares are left",
                holdUuid = userHold.holdUuid,
                executionUuid = fillInstruction.executionUuid,
                reference = fillInstruction.reference,
                amount = fillInstruction.quantity
            )
        }

        val userPositionAccountIdentifier = accountRepository.ensureAccount(
            accountType = AccountType.USER,
            assetType = AssetType.POSITION,
            assetCode = fillInstruction.assetCode,
            userUuid = userHold.userUuid
        )

        val userCashAccountIdentifier = accountRepository.ensureAccount(
            accountType = AccountType.USER,
            assetType = AssetType.CASH,
            assetCode = fillInstruction.currency.name,
            userUuid = userHold.userUuid
        )

        val venuePositionAccountIdentifier = accountRepository.ensureAccount(
            accountType = AccountType.VENUE_SETTLEMENT,
            assetType = AssetType.POSITION,
            assetCode = fillInstruction.assetCode,
        )

        val settlementCashAccountIdentifier = accountRepository.ensureAccount(
            accountType = AccountType.VENUE_SETTLEMENT,
            assetType = AssetType.CASH,
            assetCode = fillInstruction.currency.name,
        )

        val feeIncomeAccountIdentifier = accountRepository.ensureAccount(
            accountType = AccountType.FEE_INCOME,
            assetType = AssetType.CASH,
            assetCode = fillInstruction.currency.name,
        )

        val commands = buildList {
            add(SettleReservedCommand(accountRepository, userPositionAccountIdentifier.id, fillInstruction.quantity))
            add(AddToBookedCommand(accountRepository, venuePositionAccountIdentifier.id, fillInstruction.quantity))
            add(RemoveFromBookedCommand(accountRepository, settlementCashAccountIdentifier.id, fillInstruction.amount))
            add(AddToBookedCommand(accountRepository, userCashAccountIdentifier.id, fillInstruction.amount))
            if (fillInstruction.fee != 0L) {
                add(RemoveFromBookedCommand(accountRepository, userCashAccountIdentifier.id, fillInstruction.fee))
                add(AddToBookedCommand(accountRepository, feeIncomeAccountIdentifier.id, fillInstruction.fee))
            }
        }
        transferInvoker.invoke(transferId, commands)
    }
}