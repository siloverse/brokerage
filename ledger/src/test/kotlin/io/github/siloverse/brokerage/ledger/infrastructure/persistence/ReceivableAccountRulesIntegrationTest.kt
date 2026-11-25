package io.github.siloverse.brokerage.ledger.infrastructure.persistence

import io.github.siloverse.brokerage.ledger.BaseIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import java.util.UUID

/** The table rules that migration V3 adds for the customer receivable account. */
class ReceivableAccountRulesIntegrationTest : BaseIntegrationTest() {

    private fun insertAccount(type: String, user: UUID?, booked: Long = 0): Long =
        jdbc.sql(
            """
            insert into accounts (uuid, user_uuid, type, asset_type, asset_code, booked)
            values (:uuid, :user, :type, 'CASH', :code, :booked)
            returning id
            """.trimIndent()
        )
            .param("uuid", UUID.randomUUID())
            .param("user", user)
            .param("type", type)
            .param("code", "T" + UUID.randomUUID().toString().take(8).uppercase())
            .param("booked", booked)
            .query(Long::class.java).single()

    private fun refusedBy(constraint: String, statement: () -> Unit) {
        val error = assertThrows<DataIntegrityViolationException> { statement() }
        assertTrue(error.message!!.contains(constraint), "expected $constraint in: ${error.message}")
    }

    @Test
    fun `a receivable belongs to a user and may be zero`() {
        val id = insertAccount("CUSTOMER_RECEIVABLE", UUID.randomUUID())

        assertEquals(0, jdbc.sql("select booked from accounts where id = :id").param("id", id).query(Long::class.java).single())
    }

    @Test
    fun `a receivable without a user is refused`() {
        refusedBy("accounts_user_iff_user") { insertAccount("CUSTOMER_RECEIVABLE", user = null) }
    }

    @Test
    fun `a receivable above zero is refused`() {
        refusedBy("accounts_receivable_not_positive") { insertAccount("CUSTOMER_RECEIVABLE", UUID.randomUUID(), booked = 1) }
    }

    @Test
    fun `nothing can be reserved on a receivable`() {
        val id = insertAccount("CUSTOMER_RECEIVABLE", UUID.randomUUID())

        refusedBy("accounts_holds_users_only") {
            jdbc.sql("update accounts set reserved = 1 where id = :id").param("id", id).update()
        }
    }

    @Test
    fun `a house account still has no user, and a user account still cannot go below zero`() {
        refusedBy("accounts_user_iff_user") { insertAccount("FEE_INCOME", UUID.randomUUID()) }
        refusedBy("accounts_no_overdraft") { insertAccount("USER", UUID.randomUUID(), booked = -1) }
    }

    @Test
    fun `an unknown account type is still refused`() {
        refusedBy("accounts_type_known") { insertAccount("MARGIN", UUID.randomUUID()) }
    }
}
