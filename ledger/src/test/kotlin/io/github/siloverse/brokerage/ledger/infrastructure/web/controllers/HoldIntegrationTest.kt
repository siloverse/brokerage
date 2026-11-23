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

class HoldIntegrationTest : BaseIntegrationTest() {

    private data class Balance(val booked: Long, val reserved: Long, val available: Long)

    private fun hold(reference: Any?, user: UUID, amount: Long): MvcResult {
        val referenceField = if (reference == null) "" else """"reference":"$reference","""
        return mvc.perform(
            post("/api/ledger/holds")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{$referenceField"userUuid":"$user","assetType":"CASH","assetCode":"EUR","amount":$amount}""")
        ).andReturn()
    }

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

    private fun holdUuid(result: MvcResult): String =
        Regex("\"holdUuid\":\"([^\"]+)\"").find(result.response.contentAsString)!!.groupValues[1]

    private fun balance(user: UUID): Balance? =
        jdbc.sql("select booked, reserved, available from accounts where user_uuid = :user").param("user", user)
            .query { rs, _ -> Balance(rs.getLong("booked"), rs.getLong("reserved"), rs.getLong("available")) }
            .optional().orElse(null)

    private fun count(sql: String, vararg params: Pair<String, Any>): Long =
        params.fold(jdbc.sql(sql)) { spec, (name, value) -> spec.param(name, value) }.query(Long::class.java).single()

    private fun openHolds(user: UUID): Long =
        count(
            "select coalesce(sum(h.amount - h.consumed - h.released), 0) from holds h join accounts a on a.id = h.account_id where a.user_uuid = :user",
            "user" to user
        )

    @Test
    fun `a hold reserves the amount, leaves booked alone, and moves no money`() {
        val user = aUserWith(100_000)
        val reference = UUID.randomUUID()
        val transfersBefore = count("select count(*) from transfers")
        val entriesBefore = count("select count(*) from ledger_entries")

        val result = hold(reference, user, 50_500)

        assertEquals(201, result.response.status)
        assertEquals(Balance(booked = 100_000, reserved = 50_500, available = 49_500), balance(user))
        assertEquals(transfersBefore, count("select count(*) from transfers"))
        assertEquals(entriesBefore, count("select count(*) from ledger_entries"))
    }

    @Test
    fun `the hold row carries the reference, the amount, and nothing consumed or released`() {
        val user = aUserWith(100_000)
        val reference = UUID.randomUUID()

        val result = hold(reference, user, 50_500)

        assertEquals(
            1,
            count(
                "select count(*) from holds where reference = :reference and uuid = :uuid and amount = 50500 and consumed = 0 and released = 0",
                "reference" to reference, "uuid" to UUID.fromString(holdUuid(result))
            )
        )
    }

    @Test
    fun `the whole available balance can be held`() {
        val user = aUserWith(100_000)

        assertEquals(201, hold(UUID.randomUUID(), user, 100_000).response.status)
        assertEquals(Balance(booked = 100_000, reserved = 100_000, available = 0), balance(user))
    }

    @Test
    fun `more than the available balance is a 409 and leaves no hold`() {
        val user = aUserWith(100_000)
        val reference = UUID.randomUUID()

        val result = hold(reference, user, 100_001)

        assertEquals(409, result.response.status)
        assertTrue(result.response.contentAsString.contains("error"))
        assertEquals(Balance(booked = 100_000, reserved = 0, available = 100_000), balance(user))
        assertEquals(0, count("select count(*) from holds where reference = :reference", "reference" to reference))
    }

    @Test
    fun `a second hold only gets what the first one left`() {
        val user = aUserWith(100_000)

        assertEquals(201, hold(UUID.randomUUID(), user, 80_000).response.status)
        assertEquals(409, hold(UUID.randomUUID(), user, 30_000).response.status)
        assertEquals(201, hold(UUID.randomUUID(), user, 20_000).response.status)
        assertEquals(Balance(booked = 100_000, reserved = 100_000, available = 0), balance(user))
    }

    @Test
    fun `held money cannot be withdrawn, the rest can`() {
        val user = aUserWith(100_000)
        assertEquals(201, hold(UUID.randomUUID(), user, 80_000).response.status)

        assertEquals(409, money("/api/ledger/withdrawals", user, 30_000).response.status)
        assertEquals(201, money("/api/ledger/withdrawals", user, 20_000).response.status)

        assertEquals(Balance(booked = 80_000, reserved = 80_000, available = 0), balance(user))
    }

    @Test
    fun `a user who never deposited gets a 409 and no account is left behind`() {
        val user = UUID.randomUUID()

        assertEquals(409, hold(UUID.randomUUID(), user, 1).response.status)
        assertEquals(0, count("select count(*) from accounts where user_uuid = :user", "user" to user))
    }

    @Test
    fun `the same reference again answers 200 with the first hold and reserves nothing more`() {
        val user = aUserWith(100_000)
        val reference = UUID.randomUUID()

        val first = hold(reference, user, 50_500)
        val second = hold(reference, user, 50_500)

        assertEquals(201, first.response.status)
        assertEquals(200, second.response.status)
        assertEquals(holdUuid(first), holdUuid(second))
        assertEquals(Balance(booked = 100_000, reserved = 50_500, available = 49_500), balance(user))
    }

    @Test
    fun `ten holds of 30 on a balance of 100 at the same moment, exactly three succeed`() {
        val user = aUserWith(100)
        val pool = Executors.newFixedThreadPool(10)
        val start = CountDownLatch(1)

        val statuses = (1..10).map { pool.submit<Int> { start.await(); hold(UUID.randomUUID(), user, 30).response.status } }
            .also { start.countDown() }
            .map { it.get(20, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(3, statuses.count { it == 201 }, "statuses: $statuses")
        assertEquals(7, statuses.count { it == 409 }, "statuses: $statuses")
        assertEquals(Balance(booked = 100, reserved = 90, available = 10), balance(user))
        assertEquals(90, openHolds(user))
    }

    @Test
    fun `ten requests with the same reference at the same moment place exactly one hold`() {
        val user = aUserWith(100_000)
        val reference = UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(10)
        val start = CountDownLatch(1)

        val statuses = (1..10).map { pool.submit<Int> { start.await(); hold(reference, user, 10_000).response.status } }
            .also { start.countDown() }
            .map { it.get(20, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, statuses.count { it == 201 }, "statuses: $statuses")
        assertEquals(9, statuses.count { it == 200 }, "statuses: $statuses")
        assertEquals(Balance(booked = 100_000, reserved = 10_000, available = 90_000), balance(user))
    }

    @Test
    fun `holds and withdrawals at the same moment never spend the same money twice`() {
        val user = aUserWith(100)
        val pool = Executors.newFixedThreadPool(10)
        val start = CountDownLatch(1)

        val statuses = (1..10).map { i ->
            pool.submit<Int> {
                start.await()
                if (i % 2 == 0) hold(UUID.randomUUID(), user, 30).response.status
                else money("/api/ledger/withdrawals", user, 30).response.status
            }
        }
            .also { start.countDown() }
            .map { it.get(20, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(3, statuses.count { it == 201 }, "statuses: $statuses")
        assertEquals(7, statuses.count { it == 409 }, "statuses: $statuses")
        val after = balance(user)!!
        assertEquals(10, after.available)
        assertEquals(after.reserved, openHolds(user))
    }

    @Test
    fun `an amount of zero or less is a 400 and reserves nothing`() {
        val user = aUserWith(100_000)

        assertEquals(400, hold(UUID.randomUUID(), user, 0).response.status)
        assertEquals(400, hold(UUID.randomUUID(), user, -500).response.status)
        assertEquals(Balance(booked = 100_000, reserved = 0, available = 100_000), balance(user))
    }

    @Test
    fun `a missing or malformed reference is a 400`() {
        val user = aUserWith(100_000)

        assertEquals(400, hold(null, user, 10_000).response.status)
        assertEquals(400, hold("not-a-uuid", user, 10_000).response.status)
        assertEquals(Balance(booked = 100_000, reserved = 0, available = 100_000), balance(user))
    }
}
