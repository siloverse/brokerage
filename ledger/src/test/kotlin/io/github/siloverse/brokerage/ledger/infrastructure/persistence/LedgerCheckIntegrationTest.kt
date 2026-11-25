package io.github.siloverse.brokerage.ledger.infrastructure.persistence

import io.github.siloverse.brokerage.ledger.BaseIntegrationTest
import io.github.siloverse.brokerage.ledger.application.check.services.LedgerCheckService
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AccountType
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import io.github.siloverse.brokerage.ledger.domain.account.repositories.AccountRepository
import io.github.siloverse.brokerage.ledger.domain.check.model.BookedMismatch
import io.github.siloverse.brokerage.ledger.domain.check.model.LedgerCheckReport
import io.github.siloverse.brokerage.ledger.domain.check.model.ReservedMismatch
import io.github.siloverse.brokerage.ledger.domain.check.model.UnbalancedTransfer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * The check over the whole ledger. Each test damages the books on purpose and looks at what
 * the check reports. The damage is done inside a transaction that is rolled back, because
 * all integration tests share one database and the others expect healthy books.
 */
class LedgerCheckIntegrationTest : BaseIntegrationTest() {

    @Autowired
    lateinit var ledgerCheckService: LedgerCheckService

    @Autowired
    lateinit var accountRepository: AccountRepository

    @Autowired
    @Qualifier("ledgerTransactionManager")
    lateinit var transactionManager: PlatformTransactionManager

    private val user = UUID.randomUUID()

    private fun checkAfter(damage: () -> Unit): LedgerCheckReport = TransactionTemplate(transactionManager).execute {
        damage()
        val report = ledgerCheckService.check()
        it.setRollbackOnly()
        report
    }!!

    private fun aUserWith(amount: Long) {
        mvc.perform(
            post("/api/ledger/deposits")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userUuid":"$user","currency":"EUR","amount":$amount}""")
        ).andExpect(status().isCreated)
    }

    private fun aHold(amount: Long) {
        mvc.perform(
            post("/api/ledger/holds")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reference":"${UUID.randomUUID()}","userUuid":"$user","assetType":"CASH","assetCode":"EUR","amount":$amount}""")
        ).andExpect(status().isCreated)
    }

    private fun userAccount() = accountRepository.ensureAccount(AccountType.USER, AssetType.CASH, "EUR", user)

    private fun update(sql: String, vararg params: Pair<String, Any>) {
        var statement = jdbc.sql(sql)
        params.forEach { (name, value) -> statement = statement.param(name, value) }
        assertEquals(1, statement.update())
    }

    @Test
    fun `books that only real transfers and holds touched are healthy`() {
        aUserWith(100_000)
        aHold(30_000)

        val report = ledgerCheckService.check()

        assertTrue(report.healthy, "$report")
    }

    @Test
    fun `an entry without a partner is reported as an unbalanced transfer`() {
        val transferUuid = UUID.randomUUID()

        val report = checkAfter {
            val account = userAccount()
            update(
                "insert into transfers (uuid, kind, idempotency_key) values (:uuid, 'DEPOSIT', :key)",
                "uuid" to transferUuid, "key" to UUID.randomUUID(),
            )
            update(
                "insert into ledger_entries (transfer_id, account_id, amount) select id, :account, 100 from transfers where uuid = :uuid",
                "account" to account.id, "uuid" to transferUuid,
            )
            update("update accounts set booked = booked + 100 where id = :account", "account" to account.id)
        }

        assertFalse(report.healthy)
        assertEquals(listOf(UnbalancedTransfer(transferUuid, AssetType.CASH, "EUR", 100)), report.unbalancedTransfers)
        assertTrue(report.bookedMismatches.isEmpty(), "the balance follows its entries, only the transfer is wrong")
        assertTrue(report.reservedMismatches.isEmpty())
    }

    @Test
    fun `a balance changed without an entry is reported with both numbers`() {
        aUserWith(100_000)
        val account = userAccount()

        val report = checkAfter {
            update("update accounts set booked = booked + 7 where id = :account", "account" to account.id)
        }

        assertFalse(report.healthy)
        assertEquals(listOf(BookedMismatch(account.uuid, booked = 100_007, entriesSum = 100_000)), report.bookedMismatches)
        assertTrue(report.unbalancedTransfers.isEmpty(), "every transfer still sums to zero, only the copy drifted")
        assertTrue(report.reservedMismatches.isEmpty())
    }

    @Test
    fun `an account that never had an entry must have a booked balance of zero`() {
        val report = checkAfter {
            val account = userAccount()
            update("update accounts set booked = 5 where id = :account", "account" to account.id)
        }

        assertEquals(listOf(5L to 0L), report.bookedMismatches.map { it.booked to it.entriesSum })
    }

    @Test
    fun `a reservation without a hold behind it is reported`() {
        aUserWith(100_000)
        val account = userAccount()

        val report = checkAfter {
            update("update accounts set reserved = 30000 where id = :account", "account" to account.id)
        }

        assertFalse(report.healthy)
        assertEquals(listOf(ReservedMismatch(account.uuid, reserved = 30_000, openHolds = 0)), report.reservedMismatches)
        assertTrue(report.bookedMismatches.isEmpty())
    }

    @Test
    fun `an open hold that the account forgot is reported`() {
        aUserWith(100_000)
        aHold(30_000)
        aHold(20_000)
        val account = userAccount()

        val report = checkAfter {
            update("update accounts set reserved = reserved - 20000 where id = :account", "account" to account.id)
        }

        assertEquals(listOf(ReservedMismatch(account.uuid, reserved = 30_000, openHolds = 50_000)), report.reservedMismatches)
    }

    @Test
    fun `the endpoint answers with the report`() {
        mvc.perform(get("/api/ledger/check"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.healthy").value(true))
            .andExpect(jsonPath("$.unbalancedTransfers").isEmpty)
            .andExpect(jsonPath("$.bookedMismatches").isEmpty)
            .andExpect(jsonPath("$.reservedMismatches").isEmpty)
    }
}
