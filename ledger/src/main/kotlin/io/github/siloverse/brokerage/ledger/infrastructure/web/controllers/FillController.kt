package io.github.siloverse.brokerage.ledger.infrastructure.web.controllers

import io.github.siloverse.brokerage.ledger.application.fill.model.FillInstruction
import io.github.siloverse.brokerage.ledger.application.fill.services.FillService
import io.github.siloverse.brokerage.ledger.application.hold.exception.HoldNotFoundException
import io.github.siloverse.brokerage.ledger.domain.hold.exception.HoldFillException
import io.github.siloverse.brokerage.ledger.infrastructure.web.model.FillRequest
import io.github.siloverse.brokerage.ledger.infrastructure.web.model.FillResponse
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
@RequestMapping("/api/ledger/fills")
class FillController(private val fillService: FillService) {

    @PostMapping
    fun fill(
        @RequestHeader("Idempotency-Key") executionUuid: UUID,
        @RequestBody request: FillRequest,
    ): ResponseEntity<FillResponse> {
        val receipt = fillService.transfer(
            FillInstruction(
                executionUuid = executionUuid,
                reference = request.reference,
                assetCode = request.assetCode,
                quantity = request.quantity,
                currency = request.currency,
                amount = request.amount,
                fee = request.fee,
            )
        )
        val status = if (receipt.replayed) HttpStatus.OK else HttpStatus.CREATED
        return ResponseEntity.status(status).body(FillResponse(receipt.transferUuid))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(e: IllegalArgumentException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.badRequest().body(mapOf("error" to e.message))

    @ExceptionHandler(HoldNotFoundException::class)
    fun notFound(e: HoldNotFoundException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to e.message))

    @ExceptionHandler(HoldFillException::class)
    fun holdTooSmall(e: HoldFillException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to e.message))
}
