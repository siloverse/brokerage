package io.github.siloverse.brokerage.ledger.infrastructure.web.controllers

import io.github.siloverse.brokerage.ledger.application.deposit.model.Deposit
import io.github.siloverse.brokerage.ledger.application.deposit.services.DepositFundsService
import io.github.siloverse.brokerage.ledger.infrastructure.web.model.DepositRequest
import io.github.siloverse.brokerage.ledger.infrastructure.web.model.DepositResponse
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
@RequestMapping("/api/ledger/deposits")
class DepositController(private val depositFundsService: DepositFundsService) {

    @PostMapping
    fun deposit(
        @RequestHeader("Idempotency-Key") idempotencyKey: UUID,
        @RequestBody request: DepositRequest,
    ): ResponseEntity<DepositResponse> {
        val receipt = depositFundsService.deposit(
            Deposit(
                idempotencyKey = idempotencyKey,
                userUuid = request.userUuid,
                currency = request.currency,
                amount = request.amount,
            )
        )
        val status = if (receipt.replayed) HttpStatus.OK else HttpStatus.CREATED
        return ResponseEntity.status(status).body(DepositResponse(receipt.transferUuid))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(e: IllegalArgumentException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.badRequest().body(mapOf("error" to e.message))
}
