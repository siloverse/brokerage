package io.github.siloverse.brokerage.ledger.application.transfer.services

import io.github.siloverse.brokerage.ledger.application.transfer.model.TransferReceipt
import io.github.siloverse.brokerage.ledger.domain.transfer.model.TransferKind
import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.TransferRepository
import io.github.siloverse.identifiers.core.UuidV7
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Transactional(transactionManager = "ledgerTransactionManager")
abstract class TransferTemplate<I, P>(
    private val transferRepository: TransferRepository,
) {

    // the template method
    fun transfer(instruction: I): TransferReceipt {
        val prepared = prepare(instruction)
        val key = idempotencyKey(instruction)

        val transferUuid = UuidV7.next()
        val transferId = transferRepository.insert(
            uuid = transferUuid,
            kind = kind,
            idempotencyKey = key,
            holdId = holdId(prepared),
        ) ?: return TransferReceipt(transferRepository.findUuidByKey(key), replayed = true)

        book(instruction, prepared, transferId)

        return TransferReceipt(transferUuid, replayed = false)
    }

    // primitive operations
    protected abstract val kind: TransferKind
    protected abstract fun idempotencyKey(instruction: I): UUID
    protected abstract fun prepare(instruction: I): P
    protected abstract fun book(instruction: I, prepared: P, transferId: Long)

    // hook operation
    protected open fun holdId(prepared: P): Long? = null
}