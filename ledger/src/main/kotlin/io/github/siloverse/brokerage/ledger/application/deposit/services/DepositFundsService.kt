package io.github.siloverse.brokerage.ledger.application.deposit.services

import io.github.siloverse.brokerage.ledger.application.deposit.model.DepositCommand
import io.github.siloverse.brokerage.ledger.application.deposit.model.DepositReceipt
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import io.github.siloverse.brokerage.ledger.domain.transfer.model.TransferKind
import io.github.siloverse.brokerage.ledger.domain.account.repositories.AccountRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.LedgerEntryRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.TransferRepository
import io.github.siloverse.identifiers.core.UuidV7
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DepositFundsService(
    private val transferRepository: TransferRepository,
    private val accountRepository: AccountRepository,
    private val ledgerEntryRepository: LedgerEntryRepository
) {

    @Transactional(transactionManager = "ledgerTransactionManager")
    fun deposit(depositCommand: DepositCommand): DepositReceipt {

        val transferUuid = UuidV7.next()
        val transferId = transferRepository.insert(
            uuid = transferUuid,
            kind = TransferKind.DEPOSIT,
            idempotencyKey = depositCommand.idempotencyKey
        )

        if (transferId == null) {
            val existingTransferUuid = transferRepository.findUuidByKey(depositCommand.idempotencyKey)
            return DepositReceipt(
                transferUuid = existingTransferUuid,
                replayed = true
            )
        }

        val userAccountIdentifier = accountRepository.ensureUserAccount(
            userUuid = depositCommand.userUuid,
            assetType = AssetType.CASH,
            currency = depositCommand.currency
        )

        val settlementAccountIdentifier = accountRepository.ensureSettlementAccount(
            assetType = AssetType.CASH,
            currency = depositCommand.currency
        )

        ledgerEntryRepository.insert(
            transferId,
            userAccountIdentifier.id,
            depositCommand.amount
        )

        ledgerEntryRepository.insert(
            transferId,
            settlementAccountIdentifier.id,
            -depositCommand.amount
        )

        updateAccountsForDeposit(userAccountIdentifier.id, settlementAccountIdentifier.id, depositCommand)

        return DepositReceipt(
            transferUuid = transferUuid,
            replayed = false
        )
    }

    private fun updateAccountsForDeposit(
        userAccountId: Long,
        settlementAccountId: Long,
        depositCommand: DepositCommand,
    ) {
        listOf(
            userAccountId to depositCommand.amount,
            settlementAccountId to depositCommand.amount
        )
            .sortedBy { (accountId, _) -> accountId }
            .forEach { (accountId, amount) ->
                when (accountId) {
                    userAccountId -> accountRepository.addToBooked(accountId, amount)
                    settlementAccountId -> accountRepository.removeFromBooked(accountId, amount)
                }
            }
    }
}