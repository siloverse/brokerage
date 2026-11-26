package io.github.siloverse.brokerage.ledger.application.transfer.services

import io.github.siloverse.brokerage.ledger.domain.transfer.model.TransferKind
import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.TransferRepository
import io.github.siloverse.messaging.core.api.TransactionAwareAsynchronousBus
import java.util.UUID

/**
 * A ConcreteClass of the Template Method, for [TransferTemplateTest] only. The template is
 * abstract and its primitive operations are protected, so a mock cannot stand in for it.
 * This one only records which primitive operations ran, and in which order.
 */
class RecordingTransfer(
    transferRepository: TransferRepository,
    bus: TransactionAwareAsynchronousBus,
    private val key: UUID,
    private val refuseInPrepare: Boolean = false,
    private val holdId: Long? = null,
) : TransferTemplate<String, String>(transferRepository, bus) {

    val steps = ArrayList<String>()

    override val kind = TransferKind.DEPOSIT

    override fun idempotencyKey(instruction: String) = key

    override fun prepare(instruction: String): String {
        steps += "prepare($instruction)"
        require(!refuseInPrepare) { "refused before the gate" }
        return "prepared"
    }

    override fun book(instruction: String, prepared: String, transferId: Long) {
        steps += "book($instruction, $prepared, $transferId)"
    }

    override fun holdId(prepared: String): Long? = holdId
}
