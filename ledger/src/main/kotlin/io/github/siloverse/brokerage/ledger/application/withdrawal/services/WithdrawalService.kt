package io.github.siloverse.brokerage.ledger.application.withdrawal.services

import io.github.siloverse.brokerage.ledger.application.withdrawal.model.WithdrawalCommand
import io.github.siloverse.brokerage.ledger.application.withdrawal.model.WithdrawalReceipt
import io.github.siloverse.brokerage.ledger.domain.account.exception.InsufficientFundsException
import io.github.siloverse.brokerage.ledger.domain.account.model.AccountIdentifier
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import io.github.siloverse.brokerage.ledger.domain.account.repositories.AccountRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.model.TransferKind
import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.LedgerEntryRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.TransferRepository
import io.github.siloverse.identifiers.core.UuidV7
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class WithdrawalService(
    private val transferRepository: TransferRepository,
    private val accountRepository: AccountRepository,
    private val ledgerEntryRepository: LedgerEntryRepository
) {

    @Transactional(transactionManager = "ledgerTransactionManager")
    fun withdraw(withdrawalCommand: WithdrawalCommand): WithdrawalReceipt {

        val transferUuid = UuidV7.next()
        val transferId = transferRepository.insert(
            uuid = transferUuid,
            kind = TransferKind.WITHDRAWAL,
            idempotencyKey = withdrawalCommand.idempotencyKey
        )

        if (transferId == null) {
            val existingTransferUuid = transferRepository.findUuidByKey(withdrawalCommand.idempotencyKey)
            return WithdrawalReceipt(
                transferUuid = existingTransferUuid,
                replayed = true
            )
        }

        val userAccountIdentifier = accountRepository.ensureUserAccount(
            userUuid = withdrawalCommand.userUuid,
            assetType = AssetType.CASH,
            currency = withdrawalCommand.currency
        )

        val settlementAccountIdentifier = accountRepository.ensureSettlementAccount(
            assetType = AssetType.CASH,
            currency = withdrawalCommand.currency
        )

        ledgerEntryRepository.insert(
            transferId,
            userAccountIdentifier.id,
            -withdrawalCommand.amount
        )

        ledgerEntryRepository.insert(
            transferId,
            settlementAccountIdentifier.id,
            +withdrawalCommand.amount
        )

        updateAccountsForWithdrawal(userAccountIdentifier, settlementAccountIdentifier, withdrawalCommand)

        return WithdrawalReceipt(
            transferUuid = transferUuid,
            replayed = false
        )
    }

    private fun updateAccountsForWithdrawal(
        userAccountIdentifier: AccountIdentifier,
        settlementAccountIdentifier: AccountIdentifier,
        withdrawalCommand: WithdrawalCommand,
    ) {
        listOf(
            userAccountIdentifier to withdrawalCommand.amount,
            settlementAccountIdentifier to withdrawalCommand.amount
        )
            .sortedBy { (accountIdentifier, _) -> accountIdentifier.id }
            .forEach { (accountIdentifier, amount) ->
                if (accountIdentifier.id == userAccountIdentifier.id) {
                    val result = accountRepository.takeFromAvailable(accountIdentifier.id, amount)
                    if (result == 0) {
                        throw InsufficientFundsException(
                            accountUuid = userAccountIdentifier.uuid,
                            userUuid = withdrawalCommand.userUuid,
                            amount = amount
                        )
                    }
                } else {
                    accountRepository.addToBooked(accountIdentifier.id, amount)
                }
            }
    }
}