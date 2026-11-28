package io.github.siloverse.brokerage.ledger.infrastructure.web.controllers

import io.github.siloverse.brokerage.ledger.application.withdrawal.model.WithdrawalInstruction
import io.github.siloverse.brokerage.ledger.application.withdrawal.services.WithdrawalService
import io.github.siloverse.brokerage.ledger.domain.account.exception.InsufficientFundsException
import io.github.siloverse.brokerage.ledger.infrastructure.web.model.WithdrawalRequest
import io.github.siloverse.brokerage.ledger.infrastructure.web.model.WithdrawalResponse
import io.github.siloverse.brokerage.ledger.infrastructure.web.retry.RetryLostTransaction
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RetryLostTransaction
@RequestMapping("/api/ledger/withdrawals")
class WithdrawalController(private val withdrawalService: WithdrawalService) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @PostMapping
    fun withdraw(
        @RequestHeader("Idempotency-Key") idempotencyKey: UUID,
        @RequestBody request: WithdrawalRequest,
    ): ResponseEntity<WithdrawalResponse> {
        logger.info("withdrawal requested: key={} user={}", idempotencyKey, request.userUuid)
        val receipt = withdrawalService.transfer(
            WithdrawalInstruction(
                idempotencyKey = idempotencyKey,
                userUuid = request.userUuid,
                amount = request.amount,
                currency = request.currency,
            )
        )
        val status = if (receipt.replayed) HttpStatus.OK else HttpStatus.CREATED
        return ResponseEntity.status(status).body(WithdrawalResponse(receipt.transferUuid))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(e: IllegalArgumentException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.badRequest().body(mapOf("error" to e.message))

    @ExceptionHandler(InsufficientFundsException::class)
    fun insufficientFunds(e: InsufficientFundsException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to e.message))
}
