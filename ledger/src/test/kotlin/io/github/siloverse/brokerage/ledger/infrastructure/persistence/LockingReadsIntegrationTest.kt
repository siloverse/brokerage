package io.github.siloverse.brokerage.ledger.infrastructure.persistence

import io.github.siloverse.brokerage.ledger.BaseIntegrationTest
import io.github.siloverse.brokerage.ledger.domain.account.repositories.AccountRepository
import io.github.siloverse.brokerage.ledger.domain.hold.repositories.HoldRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The two `select ... for update` reads: the values they return, and that they really lock. */
class LockingReadsIntegrationTest : BaseIntegrationTest() {

    @Autowired
    lateinit var holdRepository: HoldRepository

    @Autowired
    lateinit var accountRepository: AccountRepository

    @Autowired
    @Qualifier("ledgerTransactionManager")
    lateinit var transactionManager: PlatformTransactionManager

    private fun <T> inTransaction(work: () -> T): T = TransactionTemplate(transactionManager).execute { work() }!!

    private fun aUserWith(amount: Long): UUID {
        val user = UUID.randomUUID()
        val status = mvc.perform(
            post("/api/ledger/deposits")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userUuid":"$user","currency":"EUR","amount":$amount}""")
        ).andReturn().response.status
        assertEquals(201, status)
        return user
    }

    private fun aHold(user: UUID, amount: Long): UUID {
        val reference = UUID.randomUUID()
        val status = mvc.perform(
            post("/api/ledger/holds")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reference":"$reference","userUuid":"$user","assetType":"CASH","assetCode":"EUR","amount":$amount}""")
        ).andReturn().response.status
        assertEquals(201, status)
        return reference
    }

    private fun release(reference: UUID): Int =
        mvc.perform(post("/api/ledger/holds/$reference/release")).andReturn().response.status

    private fun holdId(reference: UUID): Long =
        jdbc.sql("select id from holds where reference = :reference").param("reference", reference).query(Long::class.java).single()

    private fun accountId(user: UUID): Long =
        jdbc.sql("select id from accounts where user_uuid = :user and asset_type = 'CASH'").param("user", user).query(Long::class.java).single()

    private fun settlementAccountId(): Long =
        jdbc.sql("select id from accounts where type = 'VENUE_SETTLEMENT' and asset_type = 'CASH' and asset_code = 'EUR'").query(Long::class.java).single()

    @Test
    fun `locking a hold returns what is left of it`() {
        val user = aUserWith(100_000)
        val open = aHold(user, 50_500)
        val released = aHold(user, 20_000)
        release(released)

        assertEquals(50_500, inTransaction { holdRepository.lockRest(holdId(open)) })
        assertEquals(0, inTransaction { holdRepository.lockRest(holdId(released)) })
    }

    @Test
    fun `locking accounts returns the free balance of each, keyed by account id`() {
        val first = aUserWith(100_000)
        val second = aUserWith(40_000)
        aHold(first, 30_000)
        val ids = listOf(accountId(second), accountId(first))

        val available = inTransaction { accountRepository.lockAvailable(ids) }

        assertEquals(mapOf(accountId(first) to 70_000L, accountId(second) to 40_000L), available)
    }

    @Test
    fun `the locked accounts come back in ascending id, whatever order was asked for`() {
        val users = (1..4).map { aUserWith(1_000) }
        val ids = users.map { accountId(it) } + settlementAccountId()

        val available = inTransaction { accountRepository.lockAvailable(ids.shuffled()) }

        assertEquals(ids.sorted(), available.keys.toList())
    }

    @Test
    fun `while a hold is locked a release waits, and goes through after the commit`() {
        val user = aUserWith(100_000)
        val reference = aHold(user, 50_500)
        val id = holdId(reference)
        val locked = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        val locker = pool.submit<Long> {
            inTransaction {
                val rest = holdRepository.lockRest(id)
                locked.countDown()
                letGo.await(10, TimeUnit.SECONDS)
                rest
            }
        }
        assertTrue(locked.await(10, TimeUnit.SECONDS))
        val releasing = pool.submit<Int> { release(reference) }

        Thread.sleep(500)
        assertFalse(releasing.isDone, "the release should be waiting for the lock")

        letGo.countDown()
        assertEquals(50_500, locker.get(10, TimeUnit.SECONDS))
        assertEquals(200, releasing.get(10, TimeUnit.SECONDS))
        pool.shutdown()
    }

    @Test
    fun `while an account is locked a withdrawal waits, and goes through after the commit`() {
        val user = aUserWith(100_000)
        val id = accountId(user)
        val locked = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        val locker = pool.submit<Map<Long, Long>> {
            inTransaction {
                val available = accountRepository.lockAvailable(listOf(id))
                locked.countDown()
                letGo.await(10, TimeUnit.SECONDS)
                available
            }
        }
        assertTrue(locked.await(10, TimeUnit.SECONDS))
        val withdrawing = pool.submit<Int> {
            mvc.perform(
                post("/api/ledger/withdrawals")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"userUuid":"$user","currency":"EUR","amount":1000}""")
            ).andReturn().response.status
        }

        Thread.sleep(500)
        assertFalse(withdrawing.isDone, "the withdrawal should be waiting for the lock")

        letGo.countDown()
        assertEquals(mapOf(id to 100_000L), locker.get(10, TimeUnit.SECONDS))
        assertEquals(201, withdrawing.get(10, TimeUnit.SECONDS))
        pool.shutdown()
    }
}
