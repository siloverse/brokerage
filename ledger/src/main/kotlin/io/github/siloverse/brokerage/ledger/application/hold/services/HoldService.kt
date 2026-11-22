package io.github.siloverse.brokerage.ledger.application.hold.services

import io.github.siloverse.brokerage.ledger.application.hold.model.HoldCommand
import io.github.siloverse.brokerage.ledger.application.hold.model.HoldReceipt
import io.github.siloverse.brokerage.ledger.domain.account.exception.InsufficientFundsException
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import io.github.siloverse.brokerage.ledger.domain.account.repositories.AccountRepository
import io.github.siloverse.brokerage.ledger.domain.hold.repositories.HoldRepository
import io.github.siloverse.identifiers.core.UuidV7
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class HoldService(
    private val accountRepository: AccountRepository,
    private val holdRepository: HoldRepository
) {

    @Transactional(transactionManager = "ledgerTransactionManager")
    fun hold(holdCommand: HoldCommand): HoldReceipt {
        val accountIdentifier = accountRepository.ensureUserAccount(
            userUuid = holdCommand.userUuid,
            assetType = AssetType.CASH,
            currency = holdCommand.currency
        )

        val holdUuid = UuidV7.next()
        val holdId = holdRepository.insert(
            uuid = holdUuid,
            accountId = accountIdentifier.id,
            reference = holdCommand.reference,
            amount = holdCommand.amount
        )

        if (holdId == null) {
            val existingHoldUuid = holdRepository.findUuidByReference(holdCommand.reference)
            return HoldReceipt(
                holdUuid = existingHoldUuid,
                replayed = true
            )
        }

        val result = accountRepository.reserve(
            accountId = accountIdentifier.id,
            amount = holdCommand.amount
        )

        if (result == 0) {
            throw InsufficientFundsException(
                accountUuid = accountIdentifier.uuid,
                userUuid = holdCommand.userUuid,
                amount = holdCommand.amount,
            )
        }

        return HoldReceipt(
            holdUuid = holdUuid,
            replayed = false
        )
    }
}