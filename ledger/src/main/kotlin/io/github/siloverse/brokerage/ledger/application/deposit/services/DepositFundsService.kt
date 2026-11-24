package io.github.siloverse.brokerage.ledger.application.deposit.services

import io.github.siloverse.brokerage.ledger.application.deposit.model.DepositInstruction
import io.github.siloverse.brokerage.ledger.application.transfer.services.TransferTemplate
import io.github.siloverse.brokerage.ledger.domain.account.command.AddToBookedCommand
import io.github.siloverse.brokerage.ledger.domain.account.command.RemoveFromBookedCommand
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import io.github.siloverse.brokerage.ledger.domain.account.repositories.AccountRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.model.TransferKind
import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.TransferRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.services.TransferInvoker
import org.springframework.stereotype.Service
import java.util.*

@Service
class DepositFundsService(
    transferRepository: TransferRepository,
    private val accountRepository: AccountRepository,
    private val transferInvoker: TransferInvoker
) : TransferTemplate<DepositInstruction, Unit>(transferRepository) {

    override val kind: TransferKind = TransferKind.DEPOSIT

    override fun idempotencyKey(instruction: DepositInstruction): UUID = instruction.idempotencyKey

    override fun prepare(instruction: DepositInstruction)  = Unit

    override fun book(
        instruction: DepositInstruction,
        prepared: Unit,
        transferId: Long
    ) {
        val userAccountIdentifier = accountRepository.ensureUserAccount(
            userUuid = instruction.userUuid,
            assetType = AssetType.CASH,
            currency = instruction.currency
        )

        val settlementAccountIdentifier = accountRepository.ensureSettlementAccount(
            assetType = AssetType.CASH,
            currency = instruction.currency
        )

        transferInvoker.invoke(
            transferId,
            listOf(
                AddToBookedCommand(accountRepository, userAccountIdentifier.id, instruction.amount),
                RemoveFromBookedCommand(accountRepository, settlementAccountIdentifier.id, instruction.amount),
            )
        )
    }
}