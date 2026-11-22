package io.github.siloverse.brokerage.ledger.infrastructure.web.controllers

import io.github.siloverse.brokerage.ledger.BaseIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WithdrawalIntegrationTest : BaseIntegrationTest() {

    private fun send(path: String, key: Any?, user: UUID, amount: Long): MvcResult {
        val request = post(path)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"userUuid":"$user","currency":"EUR","amount":$amount}""")
        if (key != null) request.header("Idempotency-Key", key.toString())
        return mvc.perform(request).andReturn()
    }

    private fun withdraw(key: Any?, user: UUID, amount: Long) = send("/api/ledger/withdrawals", key, user, amount)

    private fun aUserWith(amount: Long): UUID {
        val user = UUID.randomUUID()
        assertEquals(201, send("/api/ledger/deposits", UUID.randomUUID(), user, amount).response.status)
        return user
    }

    private fun transferUuid(result: MvcResult): String =
        Regex("\"transferUuid\":\"([^\"]+)\"").find(result.response.contentAsString)!!.groupValues[1]

    private fun booked(user: UUID): Long? =
        jdbc.sql("select booked from accounts where user_uuid = :user").param("user", user).query(Long::class.java)
            .optional().orElse(null)

    private fun count(sql: String, vararg params: Pair<String, Any>): Long =
        params.fold(jdbc.sql(sql)) { spec, (name, value) -> spec.param(name, value) }.query(Long::class.java).single()

    private fun settlementBooked(): Long =
        count("select booked from accounts where type = 'VENUE_SETTLEMENT' and asset_code = 'EUR'")

    private fun accountsWhereCopyDiffersFromEntries(): Long =
        count("select count(*) from (select a.id from accounts a left join ledger_entries e on e.account_id = a.id group by a.id, a.booked having a.booked <> coalesce(sum(e.amount), 0)) broken")

    @Test
    fun `a withdrawal is booked once with one transfer, two entries that sum to zero, and both balances moved`() {
        val user = aUserWith(10_000)
        val key = UUID.randomUUID()
        val settlementBefore = settlementBooked()

        val result = withdraw(key, user, 4_000)

        assertEquals(201, result.response.status)
        assertEquals(6_000, booked(user))
        assertEquals(settlementBefore + 4_000, settlementBooked())
        assertEquals(
            1,
            count("select count(*) from transfers where idempotency_key = :key and kind = 'WITHDRAWAL'", "key" to key)
        )
        assertEquals(
            2,
            count(
                "select count(*) from ledger_entries e join transfers t on t.id = e.transfer_id where t.idempotency_key = :key",
                "key" to key
            )
        )
        assertEquals(
            0,
            count(
                "select coalesce(sum(e.amount), 0) from ledger_entries e join transfers t on t.id = e.transfer_id where t.idempotency_key = :key",
                "key" to key
            )
        )
    }

    @Test
    fun `the whole balance can be withdrawn`() {
        val user = aUserWith(10_000)

        assertEquals(201, withdraw(UUID.randomUUID(), user, 10_000).response.status)
        assertEquals(0, booked(user))
    }

    @Test
    fun `more than the balance is a 409 and writes nothing`() {
        val user = aUserWith(10_000)
        val key = UUID.randomUUID()
        val settlementBefore = settlementBooked()

        val result = withdraw(key, user, 10_001)

        assertEquals(409, result.response.status)
        assertTrue(result.response.contentAsString.contains("error"))
        assertEquals(10_000, booked(user))
        assertEquals(settlementBefore, settlementBooked())
        assertEquals(0, count("select count(*) from transfers where idempotency_key = :key", "key" to key))
    }

    @Test
    fun `a user who never deposited gets a 409 and no account is left behind`() {
        val user = UUID.randomUUID()

        assertEquals(409, withdraw(UUID.randomUUID(), user, 1).response.status)
        assertEquals(0, count("select count(*) from accounts where user_uuid = :user", "user" to user))
    }

    @Test
    fun `the same key again answers 200 with the first transfer and takes nothing more`() {
        val user = aUserWith(10_000)
        val key = UUID.randomUUID()

        val first = withdraw(key, user, 4_000)
        val second = withdraw(key, user, 4_000)

        assertEquals(201, first.response.status)
        assertEquals(200, second.response.status)
        assertEquals(transferUuid(first), transferUuid(second))
        assertEquals(6_000, booked(user))
    }

    @Test
    fun `a refused key is forgotten, so the same key succeeds once the money is there`() {
        val user = UUID.randomUUID()
        val key = UUID.randomUUID()

        assertEquals(409, withdraw(key, user, 4_000).response.status)
        assertEquals(201, send("/api/ledger/deposits", UUID.randomUUID(), user, 10_000).response.status)

        assertEquals(201, withdraw(key, user, 4_000).response.status)
        assertEquals(6_000, booked(user))
    }

    @Test
    fun `ten withdrawals of 30 from a balance of 100 at the same moment, exactly three succeed`() {
        val user = aUserWith(100)
        val pool = Executors.newFixedThreadPool(10)
        val start = CountDownLatch(1)

        val statuses = (1..10).map { pool.submit<Int> { start.await(); withdraw(UUID.randomUUID(), user, 30).response.status } }
            .also { start.countDown() }
            .map { it.get(20, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(3, statuses.count { it == 201 }, "statuses: $statuses")
        assertEquals(7, statuses.count { it == 409 }, "statuses: $statuses")
        assertEquals(10, booked(user))
        assertEquals(0, accountsWhereCopyDiffersFromEntries())
    }

    @Test
    fun `ten requests with the same key at the same moment take the money exactly once`() {
        val user = aUserWith(10_000)
        val key = UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(10)
        val start = CountDownLatch(1)

        val statuses = (1..10).map { pool.submit<Int> { start.await(); withdraw(key, user, 1_000).response.status } }
            .also { start.countDown() }
            .map { it.get(20, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, statuses.count { it == 201 }, "statuses: $statuses")
        assertEquals(9, statuses.count { it == 200 }, "statuses: $statuses")
        assertEquals(9_000, booked(user))
    }

    @Test
    fun `deposits and withdrawals for the same user at the same moment do not deadlock and stay exact`() {
        val user = aUserWith(10_000)
        val pool = Executors.newFixedThreadPool(10)
        val start = CountDownLatch(1)

        val statuses = (1..20).map { i ->
            pool.submit<Int> {
                start.await()
                if (i % 2 == 0) send("/api/ledger/deposits", UUID.randomUUID(), user, 100).response.status
                else withdraw(UUID.randomUUID(), user, 100).response.status
            }
        }
            .also { start.countDown() }
            .map { it.get(20, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(List(20) { 201 }, statuses)
        assertEquals(10_000, booked(user))
        assertEquals(0, accountsWhereCopyDiffersFromEntries())
        assertEquals(0, count("select coalesce(sum(amount), 0) from ledger_entries"))
    }

    @Test
    fun `an amount of zero is a 400 and takes nothing`() {
        val user = aUserWith(10_000)

        assertEquals(400, withdraw(UUID.randomUUID(), user, 0).response.status)
        assertEquals(400, withdraw(UUID.randomUUID(), user, -500).response.status)
        assertEquals(10_000, booked(user))
    }

    @Test
    fun `a missing or malformed idempotency key is a 400`() {
        val user = aUserWith(10_000)

        assertEquals(400, withdraw(null, user, 1_000).response.status)
        assertEquals(400, withdraw("not-a-uuid", user, 1_000).response.status)
        assertEquals(10_000, booked(user))
    }
}
