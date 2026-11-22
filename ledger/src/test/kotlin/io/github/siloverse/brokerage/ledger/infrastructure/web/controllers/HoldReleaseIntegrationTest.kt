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

class HoldReleaseIntegrationTest : BaseIntegrationTest() {

    private data class Balance(val booked: Long, val reserved: Long, val available: Long)
    private data class HoldRow(val amount: Long, val consumed: Long, val released: Long)

    private fun release(reference: Any): MvcResult =
        mvc.perform(post("/api/ledger/holds/$reference/release")).andReturn()

    private fun money(path: String, user: UUID, amount: Long): MvcResult =
        mvc.perform(
            post(path)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userUuid":"$user","currency":"EUR","amount":$amount}""")
        ).andReturn()

    private fun aUserWith(amount: Long): UUID {
        val user = UUID.randomUUID()
        assertEquals(201, money("/api/ledger/deposits", user, amount).response.status)
        return user
    }

    private fun aHold(user: UUID, amount: Long): UUID {
        val reference = UUID.randomUUID()
        val result = mvc.perform(
            post("/api/ledger/holds")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reference":"$reference","userUuid":"$user","currency":"EUR","amount":$amount}""")
        ).andReturn()
        assertEquals(201, result.response.status)
        return reference
    }

    /**
     * What a fill will do to the hold and to `reserved`, until the fill itself exists. It
     * leaves `booked` alone: a balance change without entries would break the books that
     * other tests check across the whole database.
     */
    private fun consume(reference: UUID, amount: Long) {
        jdbc.sql("update holds set consumed = consumed + :amount where reference = :reference")
            .param("amount", amount).param("reference", reference).update()
        jdbc.sql(
            """
            update accounts set reserved = reserved - :amount
            where id = (select account_id from holds where reference = :reference)
            """.trimIndent()
        ).param("amount", amount).param("reference", reference).update()
    }

    private fun holdUuid(result: MvcResult): String =
        Regex("\"holdUuid\":\"([^\"]+)\"").find(result.response.contentAsString)!!.groupValues[1]

    private fun balance(user: UUID): Balance =
        jdbc.sql("select booked, reserved, available from accounts where user_uuid = :user").param("user", user)
            .query { rs, _ -> Balance(rs.getLong("booked"), rs.getLong("reserved"), rs.getLong("available")) }
            .single()

    private fun holdRow(reference: UUID): HoldRow =
        jdbc.sql("select amount, consumed, released from holds where reference = :reference").param("reference", reference)
            .query { rs, _ -> HoldRow(rs.getLong("amount"), rs.getLong("consumed"), rs.getLong("released")) }
            .single()

    private fun count(sql: String, vararg params: Pair<String, Any>): Long =
        params.fold(jdbc.sql(sql)) { spec, (name, value) -> spec.param(name, value) }.query(Long::class.java).single()

    private fun openHolds(user: UUID): Long =
        count(
            "select coalesce(sum(h.amount - h.consumed - h.released), 0) from holds h join accounts a on a.id = h.account_id where a.user_uuid = :user",
            "user" to user
        )

    @Test
    fun `a release gives the whole untouched hold back and moves no money`() {
        val user = aUserWith(100_000)
        val reference = aHold(user, 50_500)
        val transfersBefore = count("select count(*) from transfers")
        val entriesBefore = count("select count(*) from ledger_entries")

        val result = release(reference)

        assertEquals(200, result.response.status)
        assertEquals(Balance(booked = 100_000, reserved = 0, available = 100_000), balance(user))
        assertEquals(HoldRow(amount = 50_500, consumed = 0, released = 50_500), holdRow(reference))
        assertEquals(transfersBefore, count("select count(*) from transfers"))
        assertEquals(entriesBefore, count("select count(*) from ledger_entries"))
    }

    @Test
    fun `the answer names the hold that was released`() {
        val user = aUserWith(100_000)
        val reference = aHold(user, 50_500)

        val result = release(reference)

        assertEquals(
            1,
            count(
                "select count(*) from holds where reference = :reference and uuid = :uuid",
                "reference" to reference, "uuid" to UUID.fromString(holdUuid(result))
            )
        )
    }

    @Test
    fun `a partly consumed hold gives back only the rest`() {
        val user = aUserWith(100_000)
        val reference = aHold(user, 50_500)
        val other = aHold(user, 40_000)
        consume(reference, 20_200)

        assertEquals(200, release(reference).response.status)

        assertEquals(HoldRow(amount = 50_500, consumed = 20_200, released = 30_300), holdRow(reference))
        assertEquals(Balance(booked = 100_000, reserved = 40_000, available = 60_000), balance(user))
        assertEquals(HoldRow(amount = 40_000, consumed = 0, released = 0), holdRow(other))
        assertEquals(balance(user).reserved, openHolds(user))
    }

    @Test
    fun `a fully consumed hold has nothing left to release`() {
        val user = aUserWith(100_000)
        val reference = aHold(user, 50_500)
        consume(reference, 50_500)

        assertEquals(200, release(reference).response.status)

        assertEquals(HoldRow(amount = 50_500, consumed = 50_500, released = 0), holdRow(reference))
        assertEquals(Balance(booked = 100_000, reserved = 0, available = 100_000), balance(user))
    }

    @Test
    fun `releasing one hold leaves the user's other holds alone`() {
        val user = aUserWith(100_000)
        val first = aHold(user, 30_000)
        val second = aHold(user, 20_000)

        assertEquals(200, release(first).response.status)

        assertEquals(Balance(booked = 100_000, reserved = 20_000, available = 80_000), balance(user))
        assertEquals(HoldRow(amount = 20_000, consumed = 0, released = 0), holdRow(second))
    }

    @Test
    fun `a second release answers 200 with the same hold and frees nothing more`() {
        val user = aUserWith(100_000)
        val released = aHold(user, 30_000)
        aHold(user, 20_000)

        val first = release(released)
        val second = release(released)

        assertEquals(200, first.response.status)
        assertEquals(200, second.response.status)
        assertEquals(holdUuid(first), holdUuid(second))
        assertEquals(Balance(booked = 100_000, reserved = 20_000, available = 80_000), balance(user))
    }

    @Test
    fun `released money can be withdrawn and held again`() {
        val user = aUserWith(100_000)
        val reference = aHold(user, 100_000)
        assertEquals(409, money("/api/ledger/withdrawals", user, 1).response.status)

        assertEquals(200, release(reference).response.status)

        assertEquals(201, money("/api/ledger/withdrawals", user, 40_000).response.status)
        aHold(user, 60_000)
        assertEquals(Balance(booked = 60_000, reserved = 60_000, available = 0), balance(user))
    }

    @Test
    fun `an unknown reference is a 404`() {
        val result = release(UUID.randomUUID())

        assertEquals(404, result.response.status)
        assertTrue(result.response.contentAsString.contains("error"))
    }

    @Test
    fun `a malformed reference is a 400`() {
        assertEquals(400, release("not-a-uuid").response.status)
    }

    @Test
    fun `ten releases of one hold at the same moment free the money exactly once`() {
        val user = aUserWith(100_000)
        val reference = aHold(user, 30_000)
        aHold(user, 20_000)
        val pool = Executors.newFixedThreadPool(10)
        val start = CountDownLatch(1)

        val statuses = (1..10).map { pool.submit<Int> { start.await(); release(reference).response.status } }
            .also { start.countDown() }
            .map { it.get(20, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(List(10) { 200 }, statuses)
        assertEquals(Balance(booked = 100_000, reserved = 20_000, available = 80_000), balance(user))
        assertEquals(20_000, openHolds(user))
    }

    @Test
    fun `releases and new holds at the same moment keep reserved equal to the open holds`() {
        val user = aUserWith(100_000)
        val toRelease = (1..5).map { aHold(user, 10_000) }
        val pool = Executors.newFixedThreadPool(10)
        val start = CountDownLatch(1)

        val tasks = toRelease.map { reference -> pool.submit<Int> { start.await(); release(reference).response.status } } +
            (1..5).map {
                pool.submit<Int> {
                    start.await()
                    mvc.perform(
                        post("/api/ledger/holds")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"reference":"${UUID.randomUUID()}","userUuid":"$user","currency":"EUR","amount":10000}""")
                    ).andReturn().response.status
                }
            }
        start.countDown()
        val statuses = tasks.map { it.get(20, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(5, statuses.count { it == 200 }, "statuses: $statuses")
        assertEquals(5, statuses.count { it == 201 }, "statuses: $statuses")
        assertEquals(Balance(booked = 100_000, reserved = 50_000, available = 50_000), balance(user))
        assertEquals(50_000, openHolds(user))
    }
}
