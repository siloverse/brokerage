package io.github.siloverse.brokerage.ledger.application.deposit.services

import io.github.siloverse.brokerage.ledger.application.deposit.model.DepositInstruction
import io.github.siloverse.brokerage.ledger.application.deposit.model.DepositReceipt
import io.github.siloverse.brokerage.ledger.domain.account.command.AddToBookedCommand
import io.github.siloverse.brokerage.ledger.domain.account.command.RemoveFromBookedCommand
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import io.github.siloverse.brokerage.ledger.domain.transfer.model.TransferKind
import io.github.siloverse.brokerage.ledger.domain.account.repositories.AccountRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.TransferRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.services.TransferInvoker
import io.github.siloverse.identifiers.core.UuidV7
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DepositFundsService(
    private val transferRepository: TransferRepository,
    private val accountRepository: AccountRepository,
    private val transferInvoker: TransferInvoker
) {

    @Transactional(transactionManager = "ledgerTransactionManager")
    fun deposit(depositInstruction: DepositInstruction): DepositReceipt {

        val transferUuid = UuidV7.next()
        val transferId = transferRepository.insert(
            uuid = transferUuid,
            kind = TransferKind.DEPOSIT,
            idempotencyKey = depositInstruction.idempotencyKey
        )

        if (transferId == null) {
            val existingTransferUuid = transferRepository.findUuidByKey(depositInstruction.idempotencyKey)
            return DepositReceipt(
                transferUuid = existingTransferUuid,
                replayed = true
            )
        }

        val userAccountIdentifier = accountRepository.ensureUserAccount(
            userUuid = depositInstruction.userUuid,
            assetType = AssetType.CASH,
            currency = depositInstruction.currency
        )

        val settlementAccountIdentifier = accountRepository.ensureSettlementAccount(
            assetType = AssetType.CASH,
            currency = depositInstruction.currency
        )

        transferInvoker.invoke(
            transferId,
            listOf(
                AddToBookedCommand(accountRepository, userAccountIdentifier.id, depositInstruction.amount),
                RemoveFromBookedCommand(accountRepository, settlementAccountIdentifier.id, depositInstruction.amount),
            )
        )

        return DepositReceipt(
            transferUuid = transferUuid,
            replayed = false
        )
    }
}