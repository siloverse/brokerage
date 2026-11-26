package io.github.siloverse.brokerage.ledger.infrastructure.web.controllers

import io.github.siloverse.brokerage.ledger.application.hold.exception.HoldNotFoundException
import io.github.siloverse.brokerage.ledger.application.hold.model.HoldInstruction
import io.github.siloverse.brokerage.ledger.application.hold.services.HoldService
import io.github.siloverse.brokerage.ledger.domain.account.exception.InsufficientFundsException
import io.github.siloverse.brokerage.ledger.infrastructure.web.model.HoldRequest
import io.github.siloverse.brokerage.ledger.infrastructure.web.model.HoldResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/ledger/holds")
class HoldController(private val holdService: HoldService) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @PostMapping
    fun hold(@RequestBody request: HoldRequest): ResponseEntity<HoldResponse> {
        logger.info("hold requested: reference={} user={}", request.reference, request.userUuid)
        val receipt = holdService.hold(
            HoldInstruction(
                reference = request.reference,
                userUuid = request.userUuid,
                assetType = request.assetType,
                assetCode = request.assetCode,
                amount = request.amount,
            )
        )
        val status = if (receipt.replayed) HttpStatus.OK else HttpStatus.CREATED
        return ResponseEntity.status(status).body(HoldResponse(receipt.holdUuid))
    }

    @PostMapping("/{reference}/release")
    fun release(@PathVariable reference: UUID): ResponseEntity<HoldResponse> {
        logger.info("hold release requested: reference={}", reference)
        val released = holdService.releaseHold(reference)
        return ResponseEntity.ok(HoldResponse(released.holdUuid))
    }

    @ExceptionHandler(HoldNotFoundException::class)
    fun notFound(e: HoldNotFoundException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to e.message))

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(e: IllegalArgumentException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.badRequest().body(mapOf("error" to e.message))

    @ExceptionHandler(InsufficientFundsException::class)
    fun insufficientFunds(e: InsufficientFundsException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to e.message))
}
