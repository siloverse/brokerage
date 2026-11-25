package io.github.siloverse.brokerage.ledger.infrastructure.web.controllers

import io.github.siloverse.brokerage.ledger.application.check.services.LedgerCheckService
import io.github.siloverse.brokerage.ledger.infrastructure.web.model.LedgerCheckResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/ledger/check")
class LedgerCheckController(private val ledgerCheckService: LedgerCheckService) {

    @GetMapping
    fun check(): LedgerCheckResponse = LedgerCheckResponse.from(ledgerCheckService.check())
}
