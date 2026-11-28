package io.github.siloverse.brokerage.ledger.infrastructure.persistence.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Isolation

class LedgerDataSourcePropertiesTest {

    private fun properties(isolation: Isolation? = null) =
        if (isolation == null) LedgerDataSourceProperties("url", "user", "password")
        else LedgerDataSourceProperties("url", "user", "password", isolation = isolation)

    @Test
    fun `the ledger runs at read committed unless told otherwise`() {
        assertEquals(Isolation.READ_COMMITTED, properties().isolation)
        assertEquals("TRANSACTION_READ_COMMITTED", properties().jdbcIsolation)
    }

    @Test
    fun `a chosen level is handed to the pool in the driver's words`() {
        assertEquals("TRANSACTION_SERIALIZABLE", properties(Isolation.SERIALIZABLE).jdbcIsolation)
        assertEquals("TRANSACTION_REPEATABLE_READ", properties(Isolation.REPEATABLE_READ).jdbcIsolation)
    }

    @Test
    fun `the database's own default means the pool sets nothing`() {
        assertNull(properties(Isolation.DEFAULT).jdbcIsolation)
    }
}
