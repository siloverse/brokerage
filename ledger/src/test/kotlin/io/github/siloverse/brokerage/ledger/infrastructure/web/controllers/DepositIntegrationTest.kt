package io.github.siloverse.brokerage.ledger.infrastructure.web.controllers

import io.github.siloverse.brokerage.ledger.BaseIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DepositIntegrationTest : BaseIntegrationTest() {

    private fun deposit(key: Any?, user: UUID, amount: Long, currency: String = "EUR"): MvcResult {
        val request = post("/api/ledger/deposits")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"userUuid":"$user","currency":"$currency","amount":$amount}""")
        if (key != null) request.header("Idempotency-Key", key.toString())
        return mvc.perform(request).andReturn()
    }

    private fun transferUuid(result: MvcResult): String =
        Regex("\"transferUuid\":\"([^\"]+)\"").find(result.response.contentAsString)!!.groupValues[1]

    private fun booked(user: UUID): Long? =
        jdbc.sql("select booked from accounts where user_uuid = :user").param("user", user).query(Long::class.java)
            .optional().orElse(null)

    private fun count(sql: String, vararg params: Pair<String, Any>): Long =
        params.fold(jdbc.sql(sql)) { spec, (name, value) -> spec.param(name, value) }.query(Long::class.java).single()

    @Test
    fun `a deposit is booked once with one transfer, two entries that sum to zero, and both balances moved`() {
        val user = UUID.randomUUID()
        val key = UUID.randomUUID()
        val settlementBefore = count("select coalesce(sum(booked), 0) from accounts where type = 'VENUE_SETTLEMENT'")

        val result = deposit(key, user, 10_000)

        assertEquals(201, result.response.status)
        assertEquals(10_000, booked(user))
        assertEquals(
            settlementBefore - 10_000,
            count("select booked from accounts where type = 'VENUE_SETTLEMENT' and asset_code = 'EUR'")
        )
        assertEquals(1, count("select count(*) from transfers where idempotency_key = :key", "key" to key))
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
    fun `the same key again answers 200 with the first transfer and books nothing`() {
        val user = UUID.randomUUID()
        val key = UUID.randomUUID()

        val first = deposit(key, user, 10_000)
        val second = deposit(key, user, 10_000)

        assertEquals(201, first.response.status)
        assertEquals(200, second.response.status)
        assertEquals(transferUuid(first), transferUuid(second))
        assertEquals(10_000, booked(user))
        assertEquals(1, count("select count(*) from transfers where idempotency_key = :key", "key" to key))
    }

    @Test
    fun `two different keys are two deposits`() {
        val user = UUID.randomUUID()

        val first = deposit(UUID.randomUUID(), user, 10_000)
        val second = deposit(UUID.randomUUID(), user, 2_500)

        assertEquals(201, second.response.status)
        assertNotEquals(transferUuid(first), transferUuid(second))
        assertEquals(12_500, booked(user))
        assertEquals(1, count("select count(*) from accounts where user_uuid = :user", "user" to user))
    }

    @Test
    fun `ten requests with the same key at the same moment book exactly once`() {
        val user = UUID.randomUUID()
        val key = UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(10)
        val start = CountDownLatch(1)

        val statuses = (1..10).map { pool.submit<Int> { start.await(); deposit(key, user, 10_000).response.status } }
            .also { start.countDown() }
            .map { it.get(20, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, statuses.count { it == 201 }, "statuses: $statuses")
        assertEquals(9, statuses.count { it == 200 }, "statuses: $statuses")
        assertEquals(10_000, booked(user))
        assertEquals(1, count("select count(*) from transfers where idempotency_key = :key", "key" to key))
    }

    @Test
    fun `twenty deposits for different users at the same moment all book, and the settlement account stays exact`() {
        val users = (1..20).map { UUID.randomUUID() }
        val pool = Executors.newFixedThreadPool(10)
        val start = CountDownLatch(1)

        val statuses = users.map { user ->
            pool.submit<Int> {
                start.await(); deposit(
                UUID.randomUUID(),
                user,
                1_000
            ).response.status
            }
        }
            .also { start.countDown() }
            .map { it.get(20, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(List(20) { 201 }, statuses)
        users.forEach { assertEquals(1_000, booked(it)) }
        // the copy equals the truth on every account, including the shared settlement row
        assertEquals(
            0,
            count("select count(*) from (select a.id from accounts a left join ledger_entries e on e.account_id = a.id group by a.id, a.booked having a.booked <> coalesce(sum(e.amount), 0)) broken")
        )
        assertEquals(0, count("select coalesce(sum(amount), 0) from ledger_entries"))
    }

    @Test
    fun `an amount of zero is a 400 and writes nothing`() {
        val user = UUID.randomUUID()
        val key = UUID.randomUUID()

        val result = deposit(key, user, 0)

        assertEquals(400, result.response.status)
        assertEquals(null, booked(user))
        assertEquals(0, count("select count(*) from transfers where idempotency_key = :key", "key" to key))
    }

    @Test
    fun `a missing or malformed idempotency key is a 400`() {
        val user = UUID.randomUUID()

        assertEquals(400, deposit(null, user, 10_000).response.status)
        assertEquals(400, deposit("not-a-uuid", user, 10_000).response.status)
        assertEquals(null, booked(user))
    }

    @Test
    fun `an unknown currency is a 400`() {
        assertEquals(400, deposit(UUID.randomUUID(), UUID.randomUUID(), 10_000, currency = "USD").response.status)
    }
}
