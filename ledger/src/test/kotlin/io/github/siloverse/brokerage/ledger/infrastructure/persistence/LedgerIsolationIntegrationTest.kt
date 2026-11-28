package io.github.siloverse.brokerage.ledger.infrastructure.persistence

import io.github.siloverse.brokerage.ledger.BaseIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** What the ledger's connections are really set to, asked from the database itself. */
class LedgerIsolationIntegrationTest : BaseIntegrationTest() {

    @Test
    fun `a ledger connection runs at read committed by default`() {
        assertEquals("read committed", jdbc.sql("show transaction_isolation").query(String::class.java).single())
    }

    @Test
    fun `a ledger connection gives up waiting for a lock after two seconds`() {
        assertEquals("2s", jdbc.sql("show lock_timeout").query(String::class.java).single())
    }
}
