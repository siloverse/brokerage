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

class FillIntegrationTest : BaseIntegrationTest() {

    private data class Balance(val booked: Long, val reserved: Long, val available: Long)
    private data class HoldRow(val amount: Long, val consumed: Long, val released: Long)

    /** Every test trades its own instrument, so the venue's position account is its own too. */
    private fun anInstrument(): String = "T" + UUID.randomUUID().toString().take(8).uppercase()

    private fun fill(
        execution: Any?, reference: UUID, instrument: String, quantity: Long, amount: Long, fee: Long,
    ): MvcResult {
        val request = post("/api/ledger/fills")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"reference":"$reference","assetCode":"$instrument","currency":"EUR","quantity":$quantity,"amount":$amount,"fee":$fee}""")
        if (execution != null) request.header("Idempotency-Key", execution.toString())
        return mvc.perform(request).andReturn()
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

    private fun aHold(user: UUID, amount: Long): UUID {
        val reference = UUID.randomUUID()
        val result = mvc.perform(
            post("/api/ledger/holds")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reference":"$reference","userUuid":"$user","assetType":"CASH","assetCode":"EUR","amount":$amount}""")
        ).andReturn()
        assertEquals(201, result.response.status)
        return reference
    }

    private fun release(reference: UUID): Int =
        mvc.perform(post("/api/ledger/holds/$reference/release")).andReturn().response.status

    private fun transferUuid(result: MvcResult): UUID =
        UUID.fromString(Regex("\"transferUuid\":\"([^\"]+)\"").find(result.response.contentAsString)!!.groupValues[1])

    private fun cash(user: UUID): Balance =
        jdbc.sql("select booked, reserved, available from accounts where user_uuid = :user and asset_type = 'CASH'")
            .param("user", user)
            .query { rs, _ -> Balance(rs.getLong("booked"), rs.getLong("reserved"), rs.getLong("available")) }
            .single()

    private fun shares(user: UUID, instrument: String): Long =
        jdbc.sql("select booked from accounts where user_uuid = :user and asset_type = 'POSITION' and asset_code = :code")
            .param("user", user).param("code", instrument)
            .query(Long::class.java).optional().orElse(0L)

    private fun house(type: String, assetType: String, code: String): Long =
        jdbc.sql("select booked from accounts where type = :type and asset_type = :asset_type and asset_code = :code")
            .param("type", type).param("asset_type", assetType).param("code", code)
            .query(Long::class.java).optional().orElse(0L)

    private fun holdRow(reference: UUID): HoldRow =
        jdbc.sql("select amount, consumed, released from holds where reference = :reference").param("reference", reference)
            .query { rs, _ -> HoldRow(rs.getLong("amount"), rs.getLong("consumed"), rs.getLong("released")) }
            .single()

    private fun count(sql: String, vararg params: Pair<String, Any>): Long =
        params.fold(jdbc.sql(sql)) { spec, (name, value) -> spec.param(name, value) }.query(Long::class.java).single()

    private fun accountsWhereCopyDiffersFromEntries(): Long =
        count("select count(*) from (select a.id from accounts a left join ledger_entries e on e.account_id = a.id group by a.id, a.booked having a.booked <> coalesce(sum(e.amount), 0)) broken")

    private fun assetsThatDoNotSumToZero(): Long =
        count("select count(*) from (select a.asset_type, a.asset_code from ledger_entries e join accounts a on a.id = e.account_id group by a.asset_type, a.asset_code having sum(e.amount) <> 0) broken")

    @Test
    fun `a fill takes the money out of the reservation and gives the shares`() {
        val user = aUserWith(100_000)
        val reference = aHold(user, 50_500)
        val instrument = anInstrument()

        val result = fill(UUID.randomUUID(), reference, instrument, quantity = 4, amount = 20_000, fee = 200)

        assertEquals(201, result.response.status)
        assertEquals(Balance(booked = 79_800, reserved = 30_300, available = 49_500), cash(user))
        assertEquals(4, shares(user, instrument))
        assertEquals(HoldRow(amount = 50_500, consumed = 20_200, released = 0), holdRow(reference))
    }

    @Test
    fun `a fill leaves available where the hold had put it`() {
        val user = aUserWith(100_000)
        val reference = aHold(user, 50_500)
        val availableBefore = cash(user).available

        fill(UUID.randomUUID(), reference, anInstrument(), quantity = 4, amount = 20_000, fee = 200)

        assertEquals(availableBefore, cash(user).available)
    }

    @Test
    fun `a fill is one transfer with the hold, and six entries that sum to zero for each asset`() {
        val user = aUserWith(100_000)
        val reference = aHold(user, 50_500)
        val instrument = anInstrument()

        val transfer = transferUuid(fill(UUID.randomUUID(), reference, instrument, quantity = 4, amount = 20_000, fee = 200))

        assertEquals(
            1,
            count(
                "select count(*) from transfers t join holds h on h.id = t.hold_id where t.uuid = :uuid and t.kind = 'FILL' and h.reference = :reference",
                "uuid" to transfer, "reference" to reference
            )
        )
        assertEquals(
            6,
            count("select count(*) from ledger_entries e join transfers t on t.id = e.transfer_id where t.uuid = :uuid", "uuid" to transfer)
        )
        assertEquals(
            0,
            count(
                """
                select count(*) from (
                    select a.asset_code from ledger_entries e
                    join transfers t on t.id = e.transfer_id
                    join accounts a on a.id = e.account_id
                    where t.uuid = :uuid
                    group by a.asset_type, a.asset_code having sum(e.amount) <> 0
                ) broken
                """.trimIndent(),
                "uuid" to transfer
            )
        )
        assertEquals(
            2,
            count(
                "select count(distinct a.asset_code) from ledger_entries e join transfers t on t.id = e.transfer_id join accounts a on a.id = e.account_id where t.uuid = :uuid",
                "uuid" to transfer
            )
        )
    }

    @Test
    fun `the price goes to the venue, the fee to the broker, and the shares come from the venue`() {
        val user = aUserWith(100_000)
        val reference = aHold(user, 50_500)
        val instrument = anInstrument()
        val settlementBefore = house("VENUE_SETTLEMENT", "CASH", "EUR")
        val feeIncomeBefore = house("FEE_INCOME", "CASH", "EUR")

        fill(UUID.randomUUID(), reference, instrument, quantity = 4, amount = 20_000, fee = 200)

        assertEquals(settlementBefore + 20_000, house("VENUE_SETTLEMENT", "CASH", "EUR"))
        assertEquals(feeIncomeBefore + 200, house("FEE_INCOME", "CASH", "EUR"))
        assertEquals(-4, house("VENUE_SETTLEMENT", "POSITION", instrument))
    }

    @Test
    fun `a fill without a fee writes four entries and leaves fee income alone`() {
        val user = aUserWith(100_000)
        val reference = aHold(user, 50_500)
        val feeIncomeBefore = house("FEE_INCOME", "CASH", "EUR")

        val result = fill(UUID.randomUUID(), reference, anInstrument(), quantity = 4, amount = 20_000, fee = 0)

        assertEquals(201, result.response.status)
        assertEquals(
            4,
            count(
                "select count(*) from ledger_entries e join transfers t on t.id = e.transfer_id where t.uuid = :uuid",
                "uuid" to transferUuid(result)
            )
        )
        assertEquals(feeIncomeBefore, house("FEE_INCOME", "CASH", "EUR"))
        assertEquals(Balance(booked = 80_000, reserved = 30_500, available = 49_500), cash(user))
    }

    @Test
    fun `an order filled in two parts consumes the hold twice, and the release gives back what was not needed`() {
        val user = aUserWith(100_000)
        val reference = aHold(user, 50_500)
        val instrument = anInstrument()

        assertEquals(201, fill(UUID.randomUUID(), reference, instrument, quantity = 4, amount = 19_600, fee = 200).response.status)
        assertEquals(201, fill(UUID.randomUUID(), reference, instrument, quantity = 6, amount = 29_400, fee = 300).response.status)

        assertEquals(10, shares(user, instrument))
        assertEquals(HoldRow(amount = 50_500, consumed = 49_500, released = 0), holdRow(reference))
        assertEquals(Balance(booked = 50_500, reserved = 1_000, available = 49_500), cash(user))

        assertEquals(200, release(reference))

        assertEquals(HoldRow(amount = 50_500, consumed = 49_500, released = 1_000), holdRow(reference))
        assertEquals(Balance(booked = 50_500, reserved = 0, available = 50_500), cash(user))
    }

    @Test
    fun `a fill can use the hold to the last cent`() {
        val user = aUserWith(100_000)
        val reference = aHold(user, 50_500)

        assertEquals(201, fill(UUID.randomUUID(), reference, anInstrument(), quantity = 10, amount = 50_000, fee = 500).response.status)

        assertEquals(HoldRow(amount = 50_500, consumed = 50_500, released = 0), holdRow(reference))
        assertEquals(Balance(booked = 49_500, reserved = 0, available = 49_500), cash(user))
    }

    @Test
    fun `the same execution again answers 200 with the first transfer and books nothing more`() {
        val user = aUserWith(100_000)
        val reference = aHold(user, 50_500)
        val instrument = anInstrument()
        val execution = UUID.randomUUID()

        val first = fill(execution, reference, instrument, quantity = 4, amount = 20_000, fee = 200)
        val second = fill(execution, reference, instrument, quantity = 4, amount = 20_000, fee = 200)

        assertEquals(201, first.response.status)
        assertEquals(200, second.response.status)
        assertEquals(transferUuid(first), transferUuid(second))
        assertEquals(4, shares(user, instrument))
        assertEquals(Balance(booked = 79_800, reserved = 30_300, available = 49_500), cash(user))
        assertEquals(HoldRow(amount = 50_500, consumed = 20_200, released = 0), holdRow(reference))
    }

    @Test
    fun `a fill that needs more than the hold has left is a 409 and writes nothing`() {
        val user = aUserWith(100_000)
        val reference = aHold(user, 50_500)
        val instrument = anInstrument()
        val execution = UUID.randomUUID()

        val result = fill(execution, reference, instrument, quantity = 11, amount = 50_400, fee = 200)

        assertEquals(409, result.response.status)
        assertTrue(result.response.contentAsString.contains("error"))
        assertEquals(Balance(booked = 100_000, reserved = 50_500, available = 49_500), cash(user))
        assertEquals(0, shares(user, instrument))
        assertEquals(HoldRow(amount = 50_500, consumed = 0, released = 0), holdRow(reference))
        assertEquals(0, count("select count(*) from transfers where idempotency_key = :key", "key" to execution))
    }

    @Test
    fun `a fill that arrives after the release is a 409 today`() {
        val user = aUserWith(100_000)
        val reference = aHold(user, 50_500)
        assertEquals(200, release(reference))

        val result = fill(UUID.randomUUID(), reference, anInstrument(), quantity = 4, amount = 20_000, fee = 200)

        assertEquals(409, result.response.status)
        assertEquals(Balance(booked = 100_000, reserved = 0, available = 100_000), cash(user))
    }

    @Test
    fun `an unknown reference is a 404 and writes nothing`() {
        val execution = UUID.randomUUID()

        val result = fill(execution, UUID.randomUUID(), anInstrument(), quantity = 4, amount = 20_000, fee = 200)

        assertEquals(404, result.response.status)
        assertEquals(0, count("select count(*) from transfers where idempotency_key = :key", "key" to execution))
    }

    @Test
    fun `invalid numbers, a blank instrument or a bad key are a 400 and change nothing`() {
        val user = aUserWith(100_000)
        val reference = aHold(user, 50_500)
        val instrument = anInstrument()

        assertEquals(400, fill(UUID.randomUUID(), reference, instrument, quantity = 0, amount = 20_000, fee = 200).response.status)
        assertEquals(400, fill(UUID.randomUUID(), reference, instrument, quantity = 4, amount = 0, fee = 200).response.status)
        assertEquals(400, fill(UUID.randomUUID(), reference, instrument, quantity = 4, amount = -20_000, fee = 200).response.status)
        assertEquals(400, fill(UUID.randomUUID(), reference, instrument, quantity = 4, amount = 20_000, fee = -1).response.status)
        assertEquals(400, fill(UUID.randomUUID(), reference, " ", quantity = 4, amount = 20_000, fee = 200).response.status)
        assertEquals(400, fill(null, reference, instrument, quantity = 4, amount = 20_000, fee = 200).response.status)
        assertEquals(400, fill("not-a-uuid", reference, instrument, quantity = 4, amount = 20_000, fee = 200).response.status)

        assertEquals(Balance(booked = 100_000, reserved = 50_500, available = 49_500), cash(user))
        assertEquals(HoldRow(amount = 50_500, consumed = 0, released = 0), holdRow(reference))
    }

    @Test
    fun `ten fills of 30 on a hold of 90 at the same moment, exactly three are booked`() {
        val user = aUserWith(1_000)
        val reference = aHold(user, 90)
        val instrument = anInstrument()
        val pool = Executors.newFixedThreadPool(10)
        val start = CountDownLatch(1)

        val statuses = (1..10).map {
            pool.submit<Int> {
                start.await()
                fill(UUID.randomUUID(), reference, instrument, quantity = 1, amount = 29, fee = 1).response.status
            }
        }
            .also { start.countDown() }
            .map { it.get(20, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(3, statuses.count { it == 201 }, "statuses: $statuses")
        assertEquals(7, statuses.count { it == 409 }, "statuses: $statuses")
        assertEquals(3, shares(user, instrument))
        assertEquals(HoldRow(amount = 90, consumed = 90, released = 0), holdRow(reference))
        assertEquals(Balance(booked = 910, reserved = 0, available = 910), cash(user))
    }

    @Test
    fun `ten deliveries of the same execution at the same moment are booked exactly once`() {
        val user = aUserWith(100_000)
        val reference = aHold(user, 50_500)
        val instrument = anInstrument()
        val execution = UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(10)
        val start = CountDownLatch(1)

        val statuses = (1..10).map {
            pool.submit<Int> {
                start.await()
                fill(execution, reference, instrument, quantity = 4, amount = 20_000, fee = 200).response.status
            }
        }
            .also { start.countDown() }
            .map { it.get(20, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, statuses.count { it == 201 }, "statuses: $statuses")
        assertEquals(9, statuses.count { it == 200 }, "statuses: $statuses")
        assertEquals(4, shares(user, instrument))
        assertEquals(Balance(booked = 79_800, reserved = 30_300, available = 49_500), cash(user))
    }

    @Test
    fun `fills, deposits and withdrawals for one user at the same moment do not deadlock and keep the books exact`() {
        val user = aUserWith(100_000)
        val reference = aHold(user, 50_000)
        val instrument = anInstrument()
        val pool = Executors.newFixedThreadPool(12)
        val start = CountDownLatch(1)

        val statuses = (1..30).map { i ->
            pool.submit<Int> {
                start.await()
                when (i % 3) {
                    0 -> fill(UUID.randomUUID(), reference, instrument, quantity = 1, amount = 900, fee = 100).response.status
                    1 -> money("/api/ledger/deposits", user, 1_000).response.status
                    else -> money("/api/ledger/withdrawals", user, 1_000).response.status
                }
            }
        }
            .also { start.countDown() }
            .map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(List(30) { 201 }, statuses)
        assertEquals(10, shares(user, instrument))
        assertEquals(Balance(booked = 90_000, reserved = 40_000, available = 50_000), cash(user))
        assertEquals(HoldRow(amount = 50_000, consumed = 10_000, released = 0), holdRow(reference))
        assertEquals(0, accountsWhereCopyDiffersFromEntries())
        assertEquals(0, assetsThatDoNotSumToZero())
    }

    @Test
    fun `fills for different users in one instrument at the same moment all book, and the venue's position is exact`() {
        val instrument = anInstrument()
        val orders = (1..10).map { aUserWith(10_000).let { user -> user to aHold(user, 5_000) } }
        val pool = Executors.newFixedThreadPool(10)
        val start = CountDownLatch(1)

        val statuses = orders.map { (_, reference) ->
            pool.submit<Int> {
                start.await()
                fill(UUID.randomUUID(), reference, instrument, quantity = 2, amount = 1_900, fee = 100).response.status
            }
        }
            .also { start.countDown() }
            .map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(List(10) { 201 }, statuses)
        orders.forEach { (user, _) -> assertEquals(2, shares(user, instrument)) }
        assertEquals(-20, house("VENUE_SETTLEMENT", "POSITION", instrument))
        assertEquals(0, accountsWhereCopyDiffersFromEntries())
        assertEquals(0, assetsThatDoNotSumToZero())
    }
}
