package io.github.siloverse.brokerage.ledger.application.fill.services

import io.github.siloverse.brokerage.ledger.application.fill.model.FillInstruction
import io.github.siloverse.brokerage.ledger.application.fill.services.FillService.BookableFill
import io.github.siloverse.brokerage.ledger.application.hold.exception.HoldNotFoundException
import io.github.siloverse.brokerage.ledger.application.transfer.services.TransferTemplate
import io.github.siloverse.brokerage.ledger.domain.hold.model.UserHold
import io.github.siloverse.brokerage.ledger.domain.hold.repositories.HoldRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.model.Fill
import io.github.siloverse.brokerage.ledger.domain.transfer.model.TransferKind
import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.TransferRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.strategy.FillStrategy
import org.springframework.stereotype.Service

@Service
class FillService(
    private val holdRepository: HoldRepository,
    transferRepository: TransferRepository,
    fillStrategies: List<FillStrategy>,
) : TransferTemplate<FillInstruction, BookableFill>(transferRepository) {

    private val strategies = fillStrategies.associateBy { it.assetType }

    override val kind: TransferKind = TransferKind.FILL

    override fun idempotencyKey(instruction: FillInstruction) = instruction.executionUuid

    override fun prepare(instruction: FillInstruction): BookableFill {
        val userHold = holdRepository.findUserHoldByReference(instruction.reference)
            ?: throw HoldNotFoundException(reference = instruction.reference)
        val strategy = strategies.getValue(userHold.assetType)

        val fill = Fill(
            assetCode = instruction.assetCode,
            quantity = instruction.quantity,
            currency = instruction.currency,
            amount = instruction.amount,
            fee = instruction.fee
        )

        strategy.validate(fill, userHold)

        return BookableFill(
            fill, userHold, strategy
        )
    }

    override fun book(
        instruction: FillInstruction,
        prepared: BookableFill,
        transferId: Long
    ) {
        prepared.strategy.book(prepared.fill, prepared.userHold, transferId)
    }

    override fun holdId(prepared: BookableFill) = prepared.userHold.holdId

    data class BookableFill(
        val fill: Fill,
        val userHold: UserHold,
        val strategy: FillStrategy
    )
}