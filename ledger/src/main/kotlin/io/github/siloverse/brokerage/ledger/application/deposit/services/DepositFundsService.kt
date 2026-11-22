package io.github.siloverse.brokerage.ledger.application.deposit.services

import io.github.siloverse.brokerage.ledger.application.deposit.model.Deposit
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
    fun deposit(deposit: Deposit): DepositReceipt {

        val transferUuid = UuidV7.next()
        val transferId = transferRepository.insert(
            uuid = transferUuid,
            kind = TransferKind.DEPOSIT,
            idempotencyKey = deposit.idempotencyKey
        )

        if (transferId == null) {
            val existingTransferUuid = transferRepository.findUuidByKey(deposit.idempotencyKey)
            return DepositReceipt(
                transferUuid = existingTransferUuid,
                replayed = true
            )
        }

        val userAccountIdentifier = accountRepository.ensureUserAccount(
            userUuid = deposit.userUuid,
            assetType = AssetType.CASH,
            currency = deposit.currency
        )

        val settlementAccountIdentifier = accountRepository.ensureSettlementAccount(
            assetType = AssetType.CASH,
            currency = deposit.currency
        )

        ledgerEntryRepository.insert(
            transferId,
            userAccountIdentifier.id,
            deposit.amount
        )

        ledgerEntryRepository.insert(
            transferId,
            settlementAccountIdentifier.id,
            -deposit.amount
        )

        updateAccountsForDeposit(userAccountIdentifier.id, settlementAccountIdentifier.id, deposit)

        return DepositReceipt(
            transferUuid = transferUuid,
            replayed = false
        )
    }

    private fun updateAccountsForDeposit(
        userAccountId: Long,
        settlementAccountId: Long,
        deposit: Deposit,
    ) {
        listOf(
            userAccountId to deposit.amount,
            settlementAccountId to -deposit.amount
        )
            .sortedBy { (accountId, _) -> accountId }
            .forEach { (accountId, amount) ->
                accountRepository.addToBooked(accountId, amount)
            }
    }
}