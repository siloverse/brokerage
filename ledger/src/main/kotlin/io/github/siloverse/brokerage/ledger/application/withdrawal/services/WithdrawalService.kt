package io.github.siloverse.brokerage.ledger.application.withdrawal.services

import io.github.siloverse.brokerage.ledger.application.transfer.services.TransferTemplate
import io.github.siloverse.brokerage.ledger.application.withdrawal.model.WithdrawalInstruction
import io.github.siloverse.brokerage.ledger.domain.account.command.AddToBookedCommand
import io.github.siloverse.brokerage.ledger.domain.account.command.TakeFromAvailableCommand
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import io.github.siloverse.brokerage.ledger.domain.account.repositories.AccountRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.model.TransferKind
import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.TransferRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.services.TransferInvoker
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class WithdrawalService(
    transferRepository: TransferRepository,
    private val accountRepository: AccountRepository,
    private val transferInvoker: TransferInvoker,
) : TransferTemplate<WithdrawalInstruction, Unit>(transferRepository) {

    override val kind: TransferKind = TransferKind.WITHDRAWAL

    override fun idempotencyKey(instruction: WithdrawalInstruction) = instruction.idempotencyKey

    override fun prepare(instruction: WithdrawalInstruction) = Unit

    override fun book(
        instruction: WithdrawalInstruction,
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
            transferId = transferId,
            commands = listOf(
                TakeFromAvailableCommand(
                    receiver = accountRepository,
                    accountId = userAccountIdentifier.id,
                    amount = instruction.amount,
                    accountUuid = userAccountIdentifier.uuid,
                    userUuid = instruction.userUuid
                ),
                AddToBookedCommand(
                    receiver = accountRepository,
                    accountId = settlementAccountIdentifier.id,
                    amount = instruction.amount
                )
            )
        )
    }
}