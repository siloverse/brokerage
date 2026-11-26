package io.github.siloverse.brokerage.ledger.application.hold.services

import io.github.siloverse.brokerage.ledger.application.hold.exception.HoldNotFoundException
import io.github.siloverse.brokerage.ledger.application.hold.model.HoldInstruction
import io.github.siloverse.brokerage.ledger.application.hold.model.HoldReceipt
import io.github.siloverse.brokerage.ledger.application.hold.model.HoldReleaseReceipt
import io.github.siloverse.brokerage.ledger.domain.account.exception.InsufficientFundsException
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AccountType
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import io.github.siloverse.brokerage.ledger.domain.account.repositories.AccountRepository
import io.github.siloverse.brokerage.ledger.domain.hold.repositories.HoldRepository
import io.github.siloverse.identifiers.core.UuidV7
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class HoldService(
    private val accountRepository: AccountRepository,
    private val holdRepository: HoldRepository
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional(transactionManager = "ledgerTransactionManager")
    fun hold(holdInstruction: HoldInstruction): HoldReceipt {
        val accountIdentifier = accountRepository.ensureAccount(
            accountType = AccountType.USER,
            assetType = holdInstruction.assetType,
            assetCode = holdInstruction.assetCode,
            userUuid = holdInstruction.userUuid
        )

        val holdUuid = UuidV7.next()
        val holdId = holdRepository.insert(
            uuid = holdUuid,
            accountId = accountIdentifier.id,
            reference = holdInstruction.reference,
            amount = holdInstruction.amount
        )

        if (holdId == null) {
            logger.info("Hold is already created { HoldInstruction = $holdInstruction")
            val existingHoldUuid = holdRepository.getUuidByReference(holdInstruction.reference)
            return HoldReceipt(
                holdUuid = existingHoldUuid,
                replayed = true
            )
        }

        val result = accountRepository.reserve(
            accountId = accountIdentifier.id,
            amount = holdInstruction.amount
        )

        if (result == 0) {
            throw InsufficientFundsException(
                accountUuid = accountIdentifier.uuid,
                userUuid = holdInstruction.userUuid,
                amount = holdInstruction.amount,
            )
        }

        return HoldReceipt(
            holdUuid = holdUuid,
            replayed = false
        )
    }

    @Transactional(transactionManager = "ledgerTransactionManager")
    fun releaseHold(reference: UUID): HoldReleaseReceipt {

        val releasedHold = holdRepository.release(
            reference = reference
        )

        if (releasedHold == null) {
            val holdUuid = holdRepository.findUuidByReference(reference)
            if (holdUuid == null) {
                throw HoldNotFoundException(
                    reference = reference
                )
            } else {
                return HoldReleaseReceipt(
                    holdUuid = holdUuid,
                    reference = reference,
                    replayed = true
                )
            }
        }

        accountRepository.release(
            accountId = releasedHold.accountId,
            amount = releasedHold.released
        )

        return HoldReleaseReceipt(
            holdUuid = releasedHold.uuid,
            reference = reference,
            replayed = false
        )
    }
}