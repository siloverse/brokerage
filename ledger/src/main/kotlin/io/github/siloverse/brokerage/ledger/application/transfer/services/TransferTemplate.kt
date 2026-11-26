package io.github.siloverse.brokerage.ledger.application.transfer.services

import io.github.siloverse.brokerage.ledger.api.messages.events.TransferBooked
import io.github.siloverse.brokerage.ledger.application.transfer.model.TransferReceipt
import io.github.siloverse.brokerage.ledger.domain.transfer.model.TransferKind
import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.TransferRepository
import io.github.siloverse.identifiers.core.UuidV7
import io.github.siloverse.messaging.core.api.TransactionAwareAsynchronousBus
import org.slf4j.LoggerFactory
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Transactional(transactionManager = "ledgerTransactionManager")
abstract class TransferTemplate<I, P>(
    private val transferRepository: TransferRepository,
    private val bus: TransactionAwareAsynchronousBus
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // the template method
    fun transfer(instruction: I): TransferReceipt {
        val prepared = prepare(instruction)
        val key = idempotencyKey(instruction)

        val transferUuid = UuidV7.next()
        val holdId = holdId(prepared)
        val transferId = transferRepository.insert(
            uuid = transferUuid,
            kind = kind,
            idempotencyKey = key,
            holdId = holdId,
        )

        if (transferId == null) {
            logger.info("Transfer is already created { TransferInstruction = $instruction")
            return TransferReceipt(transferRepository.findUuidByKey(key), replayed = true)
        }

        book(instruction, prepared, transferId)
        bus.publish(
            TransferBooked(
                transferUuid = transferUuid,
                kind = kind.name
            )
        )
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