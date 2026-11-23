package io.github.siloverse.brokerage.ledger.application.fill.services

import io.github.siloverse.brokerage.ledger.application.fill.exception.HoldFillException
import io.github.siloverse.brokerage.ledger.application.fill.model.FillCommand
import io.github.siloverse.brokerage.ledger.application.fill.model.FillReceipt
import io.github.siloverse.brokerage.ledger.application.hold.exception.HoldNotFoundException
import io.github.siloverse.brokerage.ledger.domain.account.model.AccountIdentifier
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AccountType
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import io.github.siloverse.brokerage.ledger.domain.account.repositories.AccountRepository
import io.github.siloverse.brokerage.ledger.domain.hold.repositories.HoldRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.model.TransferKind
import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.LedgerEntryRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.TransferRepository
import io.github.siloverse.identifiers.core.UuidV7
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class FillService(
    private val holdRepository: HoldRepository,
    private val transferRepository: TransferRepository,
    private val accountRepository: AccountRepository,
    private val ledgerEntryRepository: LedgerEntryRepository
) {

    @Transactional(transactionManager = "ledgerTransactionManager")
    fun buyFill(fillCommand: FillCommand): FillReceipt {

        val userHold = holdRepository.findUserHoldByReference(fillCommand.reference) ?: throw HoldNotFoundException(
            reference = fillCommand.reference
        )
        val transferUuid = UuidV7.next()
        val transferId = transferRepository.insert(
            uuid = transferUuid,
            kind = TransferKind.FILL,
            idempotencyKey = fillCommand.executionUuid,
            holdId = userHold.holdId
        )

        if (transferId == null) {
            val existingTransferUuid = transferRepository.findUuidByKey(fillCommand.executionUuid)
            return FillReceipt(
                transferUuid = existingTransferUuid,
                replayed = true
            )
        }

        val updates = holdRepository.consumeHold(
            id = userHold.holdId,
            amount = fillCommand.amount + fillCommand.fee
        )

        if (updates == 0) {
            throw HoldFillException(
                message = "Unable to consume the Hold to fill position as too low consumable hold amount is left",
                holdUuid = userHold.holdUuid,
                executionUuid = fillCommand.executionUuid,
                reference = fillCommand.reference,
                amount = fillCommand.amount + fillCommand.fee
            )
        }

        val userPositionAccountIdentifier = accountRepository.ensureAccount(
            accountType = AccountType.USER,
            assetType = AssetType.POSITION,
            assetCode = fillCommand.assetCode,
            userUuid = userHold.userUuid
        )

        val userCashAccountIdentifier = accountRepository.ensureAccount(
            accountType = AccountType.USER,
            assetType = AssetType.CASH,
            assetCode = userHold.assetCode,
            userUuid = userHold.userUuid
        )

        val settlementPositionAccountIdentifier = accountRepository.ensureAccount(
            accountType = AccountType.VENUE_SETTLEMENT,
            assetType = AssetType.POSITION,
            assetCode = fillCommand.assetCode,
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

        ledgerEntryRepository.insert(
            transferId = transferId,
            accountId = userCashAccountIdentifier.id,
            amount = -fillCommand.amount
        )

        if (fillCommand.fee != 0L) {

            ledgerEntryRepository.insert(
                transferId = transferId,
                accountId = feeIncomeAccountIdentifier.id,
                amount = fillCommand.fee
            )

            ledgerEntryRepository.insert(
                transferId = transferId,
                accountId = userCashAccountIdentifier.id,
                amount = -fillCommand.fee
            )
        }

        ledgerEntryRepository.insert(
            transferId = transferId,
            accountId = settlementCashAccountIdentifier.id,
            amount = fillCommand.amount
        )

        ledgerEntryRepository.insert(
            transferId = transferId,
            accountId = userPositionAccountIdentifier.id,
            amount = fillCommand.quantity
        )

        ledgerEntryRepository.insert(
            transferId = transferId,
            accountId = settlementPositionAccountIdentifier.id,
            amount = -fillCommand.quantity
        )

        updateAccountsForFill(
            userPositionAccountIdentifier = userPositionAccountIdentifier,
            userCashAccountIdentifier = userCashAccountIdentifier,
            settlementPositionAccountIdentifier = settlementPositionAccountIdentifier,
            settlementCashAccountIdentifier = settlementCashAccountIdentifier,
            feeIncomeAccountIdentifier = feeIncomeAccountIdentifier,
            fillCommand = fillCommand
        )

        return FillReceipt(
            transferUuid = transferUuid,
            replayed = false
        )
    }

    private fun updateAccountsForFill(
        userPositionAccountIdentifier: AccountIdentifier,
        userCashAccountIdentifier: AccountIdentifier,
        settlementPositionAccountIdentifier: AccountIdentifier,
        settlementCashAccountIdentifier: AccountIdentifier,
        feeIncomeAccountIdentifier: AccountIdentifier,
        fillCommand: FillCommand
    ) {
        listOf(
            userPositionAccountIdentifier to fillCommand.quantity,
            userCashAccountIdentifier to fillCommand.amount + fillCommand.fee,
            settlementPositionAccountIdentifier to fillCommand.quantity,
            settlementCashAccountIdentifier to fillCommand.amount,
            feeIncomeAccountIdentifier to fillCommand.fee
        )
            .sortedBy { (account, _) -> account.id }
            .forEach { (account, amount) ->
                when (account.id) {
                    userCashAccountIdentifier.id -> accountRepository.settleReserved(account.id, amount)
                    settlementPositionAccountIdentifier.id -> accountRepository.removeFromBooked(account.id, amount)
                    else -> accountRepository.addToBooked(account.id, amount)
                }
            }
    }
}