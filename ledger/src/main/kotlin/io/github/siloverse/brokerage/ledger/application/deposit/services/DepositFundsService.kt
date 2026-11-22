package io.github.siloverse.brokerage.ledger.application.deposit.services

import io.github.siloverse.brokerage.ledger.application.deposit.model.Deposit
import io.github.siloverse.brokerage.ledger.application.deposit.model.DepositReceipt
import io.github.siloverse.brokerage.ledger.domain.account.model.AssetType
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

        val userAccountId = accountRepository.ensureUserAccount(
            userUuid = deposit.userUuid,
            assetType = AssetType.CASH,
            currency = deposit.currency
        )

        val settlementAccountId = accountRepository.ensureSettlementAccount(
            assetType = AssetType.CASH,
            currency = deposit.currency
        )

        ledgerEntryRepository.insert(
            transferId,
            userAccountId,
            deposit.amount
        )

        ledgerEntryRepository.insert(
            transferId,
            settlementAccountId,
            -deposit.amount
        )

        accountRepository.addToBooked(
            userAccountId,
            deposit.amount
        )
        accountRepository.addToBooked(
            settlementAccountId,
            -deposit.amount
        )


        return DepositReceipt(
            transferUuid = transferUuid,
            replayed = false
        )
    }
}