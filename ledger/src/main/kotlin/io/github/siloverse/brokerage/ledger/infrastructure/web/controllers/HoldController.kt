package io.github.siloverse.brokerage.ledger.infrastructure.web.controllers

import io.github.siloverse.brokerage.ledger.application.hold.model.HoldCommand
import io.github.siloverse.brokerage.ledger.application.hold.services.HoldService
import io.github.siloverse.brokerage.ledger.domain.account.exception.InsufficientFundsException
import io.github.siloverse.brokerage.ledger.infrastructure.web.model.HoldRequest
import io.github.siloverse.brokerage.ledger.infrastructure.web.model.HoldResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/ledger/holds")
class HoldController(private val holdService: HoldService) {

    @PostMapping
    fun hold(@RequestBody request: HoldRequest): ResponseEntity<HoldResponse> {
        val receipt = holdService.hold(
            HoldCommand(
                reference = request.reference,
                userUuid = request.userUuid,
                currency = request.currency,
                amount = request.amount,
            )
        )
        val status = if (receipt.replayed) HttpStatus.OK else HttpStatus.CREATED
        return ResponseEntity.status(status).body(HoldResponse(receipt.holdUuid))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(e: IllegalArgumentException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.badRequest().body(mapOf("error" to e.message))

    @ExceptionHandler(InsufficientFundsException::class)
    fun insufficientFunds(e: InsufficientFundsException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to e.message))
}
