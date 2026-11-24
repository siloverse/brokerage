package io.github.siloverse.brokerage.ledger.application.withdrawal.services

import io.github.siloverse.brokerage.ledger.application.withdrawal.model.WithdrawalInstruction
import io.github.siloverse.brokerage.ledger.application.withdrawal.model.WithdrawalReceipt
import io.github.siloverse.brokerage.ledger.domain.account.command.AddToBookedCommand
import io.github.siloverse.brokerage.ledger.domain.account.command.TakeFromAvailableCommand
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import io.github.siloverse.brokerage.ledger.domain.account.repositories.AccountRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.model.TransferKind
import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.TransferRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.services.TransferInvoker
import io.github.siloverse.identifiers.core.UuidV7
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class WithdrawalService(
    private val transferRepository: TransferRepository,
    private val accountRepository: AccountRepository,
    private val transferInvoker: TransferInvoker,
) {

    @Transactional(transactionManager = "ledgerTransactionManager")
    fun withdraw(withdrawalInstruction: WithdrawalInstruction): WithdrawalReceipt {

        val transferUuid = UuidV7.next()
        val transferId = transferRepository.insert(
            uuid = transferUuid,
            kind = TransferKind.WITHDRAWAL,
            idempotencyKey = withdrawalInstruction.idempotencyKey
        )

        if (transferId == null) {
            val existingTransferUuid = transferRepository.findUuidByKey(withdrawalInstruction.idempotencyKey)
            return WithdrawalReceipt(
                transferUuid = existingTransferUuid,
                replayed = true
            )
        }

        val userAccountIdentifier = accountRepository.ensureUserAccount(
            userUuid = withdrawalInstruction.userUuid,
            assetType = AssetType.CASH,
            currency = withdrawalInstruction.currency
        )

        val settlementAccountIdentifier = accountRepository.ensureSettlementAccount(
            assetType = AssetType.CASH,
            currency = withdrawalInstruction.currency
        )

        transferInvoker.invoke(
            transferId = transferId,
            commands = listOf(
                TakeFromAvailableCommand(
                    receiver = accountRepository,
                    accountId = userAccountIdentifier.id,
                    amount = withdrawalInstruction.amount,
                    accountUuid = userAccountIdentifier.uuid,
                    userUuid = withdrawalInstruction.userUuid
                ),
                AddToBookedCommand(
                    receiver = accountRepository,
                    accountId = settlementAccountIdentifier.id,
                    amount = withdrawalInstruction.amount
                )
            )
        )

        return WithdrawalReceipt(
            transferUuid = transferUuid,
            replayed = false
        )
    }

}