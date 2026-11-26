package io.github.siloverse.brokerage.ledger.infrastructure.web.controllers

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(basePackageClasses = [LedgerBusyAdvice::class])
class LedgerBusyAdvice {

    private val logger = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(UncategorizedSQLException::class)
    fun lockTimeout(e: UncategorizedSQLException): ResponseEntity<Map<String, String?>> {
        if (e.sqlException?.sqlState != LOCK_NOT_AVAILABLE) throw e
        logger.warn("ledger busy: a statement gave up waiting for a lock")
        return busy("the ledger is busy, try again")
    }

    @ExceptionHandler(CannotCreateTransactionException::class)
    fun noConnection(e: CannotCreateTransactionException): ResponseEntity<Map<String, String?>> {
        logger.warn("ledger busy: no database connection was free: {}", e.mostSpecificCause.message)
        return busy("the ledger is busy, try again")
    }

    private fun busy(message: String): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
            .body(mapOf("error" to message))

    companion object {
        const val LOCK_NOT_AVAILABLE = "55P03"
        const val RETRY_AFTER_SECONDS = "1"
    }
}
