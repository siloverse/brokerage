package io.github.siloverse.brokerage.ledger.infrastructure.web.controllers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.transaction.CannotCreateTransactionException
import java.sql.SQLException

class LedgerBusyAdviceTest {

    private val advice = LedgerBusyAdvice()

    private fun uncategorized(sqlState: String) =
        UncategorizedSQLException("statement", "update accounts", SQLException("database said no", sqlState))

    @Test
    fun `a lock timeout is answered with 503 and a hint when to try again`() {
        val response = advice.lockTimeout(uncategorized("55P03"))

        assertEquals(503, response.statusCode.value())
        assertEquals("1", response.headers.getFirst("Retry-After"))
        assertEquals(mapOf("error" to "the ledger is busy, try again"), response.body)
    }

    @Test
    fun `any other uncategorized database error stays an error`() {
        val other = uncategorized("XX000")

        val thrown = assertThrows<UncategorizedSQLException> { advice.lockTimeout(other) }

        assertSame(other, thrown)
    }

    @Test
    fun `no free connection is answered with 503 and the same hint`() {
        val noConnection = CannotCreateTransactionException(
            "Could not open JDBC Connection for transaction",
            CannotGetJdbcConnectionException("Failed to obtain JDBC Connection"),
        )

        val response = advice.noConnection(noConnection)

        assertEquals(503, response.statusCode.value())
        assertEquals("1", response.headers.getFirst("Retry-After"))
    }
}
