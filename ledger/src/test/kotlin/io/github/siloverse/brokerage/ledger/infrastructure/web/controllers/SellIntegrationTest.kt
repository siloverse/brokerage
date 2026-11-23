package io.github.siloverse.brokerage.ledger.infrastructure.web.controllers

import io.github.siloverse.brokerage.ledger.BaseIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SellIntegrationTest : BaseIntegrationTest() {

    private data class Balance(val booked: Long, val reserved: Long, val available: Long)
    private data class HoldRow(val amount: Long, val consumed: Long, val released: Long)

    private val noShares = Balance(booked = 0, reserved = 0, available = 0)

    /** Every test trades its own instrument, so the venue's position account is its own too. */
    private fun anInstrument(): String = "T" + UUID.randomUUID().toString().take(8).uppercase()

    private fun money(path: String, user: UUID, amount: Long): MvcResult =
        mvc.perform(
            post(path)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userUuid":"$user","currency":"EUR","amount":$amount}""")
        ).andReturn()

    private fun hold(reference: UUID, user: UUID, assetType: String, assetCode: String, amount: Long): Int =
        mvc.perform(
            post("/api/ledger/holds")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reference":"$reference","userUuid":"$user","assetType":"$assetType","assetCode":"$assetCode","amount":$amount}""")
        ).andReturn().response.status

    private fun aHold(user: UUID, assetType: String, assetCode: String, amount: Long): UUID {
        val reference = UUID.randomUUID()
        assertEquals(201, hold(reference, user, assetType, assetCode, amount))
        return reference
    }

    private fun aShareHold(user: UUID, instrument: String, shares: Long): UUID = aHold(user, "POSITION", instrument, shares)

    private fun fill(
        execution: UUID, reference: UUID, instrument: String, quantity: Long, amount: Long, fee: Long,
    ): MvcResult =
        mvc.perform(
            post("/api/ledger/fills")
                .header("Idempotency-Key", execution.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reference":"$reference","assetCode":"$instrument","currency":"EUR","quantity":$quantity,"amount":$amount,"fee":$fee}""")
        ).andReturn()

    private fun release(reference: UUID): Int =
        mvc.perform(post("/api/ledger/holds/$reference/release")).andReturn().response.status

    /** A user with 90,000 in cash and the given shares, bought through a real hold and fill. */
    private fun aUserWithShares(instrument: String, shares: Long): UUID {
        val user = UUID.randomUUID()
        assertEquals(201, money("/api/ledger/deposits", user, 100_000).response.status)
        val buy = aHold(user, "CASH", "EUR", 10_000)
        assertEquals(201, fill(UUID.randomUUID(), buy, instrument, quantity = shares, amount = 10_000, fee = 0).response.status)
        return user
    }

    private fun transferUuid(result: MvcResult): UUID =
        UUID.fromString(Regex("\"transferUuid\":\"([^\"]+)\"").find(result.response.contentAsString)!!.groupValues[1])

    private fun balance(user: UUID, assetType: String, code: String): Balance =
        jdbc.sql("select booked, reserved, available from accounts where user_uuid = :user and asset_type = :type and asset_code = :code")
            .param("user", user).param("type", assetType).param("code", code)
            .query { rs, _ -> Balance(rs.getLong("booked"), rs.getLong("reserved"), rs.getLong("available")) }
            .optional().orElse(noShares)

    private fun cash(user: UUID) = balance(user, "CASH", "EUR")
    private fun shares(user: UUID, instrument: String) = balance(user, "POSITION", instrument)

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

    // ---- a hold on shares

    @Test
    fun `a hold on shares reserves them, leaves booked alone, and moves nothing`() {
        val instrument = anInstrument()
        val user = aUserWithShares(instrument, 10)
        val entriesBefore = count("select count(*) from ledger_entries")

        aShareHold(user, instrument, 6)

        assertEquals(Balance(booked = 10, reserved = 6, available = 4), shares(user, instrument))
        assertEquals(Balance(booked = 90_000, reserved = 0, available = 90_000), cash(user))
        assertEquals(entriesBefore, count("select count(*) from ledger_entries"))
    }

    @Test
    fun `more shares than the user owns is a 409`() {
        val instrument = anInstrument()
        val user = aUserWithShares(instrument, 10)

        assertEquals(409, hold(UUID.randomUUID(), user, "POSITION", instrument, 11))
        assertEquals(Balance(booked = 10, reserved = 0, available = 10), shares(user, instrument))
    }

    @Test
    fun `a user who owns none of the instrument gets a 409 and no position account is left behind`() {
        val instrument = anInstrument()
        val user = aUserWithShares(anInstrument(), 10)

        assertEquals(409, hold(UUID.randomUUID(), user, "POSITION", instrument, 1))
        assertEquals(
            0,
            count("select count(*) from accounts where user_uuid = :user and asset_code = :code", "user" to user, "code" to instrument)
        )
    }

    @Test
    fun `shares that are held cannot be held a second time, the rest can`() {
        val instrument = anInstrument()
        val user = aUserWithShares(instrument, 10)
        aShareHold(user, instrument, 6)

        assertEquals(409, hold(UUID.randomUUID(), user, "POSITION", instrument, 5))
        assertEquals(201, hold(UUID.randomUUID(), user, "POSITION", instrument, 4))
        assertEquals(Balance(booked = 10, reserved = 10, available = 0), shares(user, instrument))
    }

    @Test
    fun `releasing a share hold gives the shares back`() {
        val instrument = anInstrument()
        val user = aUserWithShares(instrument, 10)
        val reference = aShareHold(user, instrument, 6)

        assertEquals(200, release(reference))

        assertEquals(Balance(booked = 10, reserved = 0, available = 10), shares(user, instrument))
        assertEquals(HoldRow(amount = 6, consumed = 0, released = 6), holdRow(reference))
    }

    @Test
    fun `a cash hold in an unknown currency, a blank code or an unknown asset type is a 400`() {
        val user = aUserWithShares(anInstrument(), 10)

        assertEquals(400, hold(UUID.randomUUID(), user, "CASH", "EURO", 1_000))
        assertEquals(400, hold(UUID.randomUUID(), user, "POSITION", " ", 1))
        assertEquals(400, hold(UUID.randomUUID(), user, "GOLD", "XAU", 1))
        assertEquals(Balance(booked = 90_000, reserved = 0, available = 90_000), cash(user))
    }

    // ---- the sell fill

    @Test
    fun `a sell takes the shares out of the reservation and pays the price minus the fee`() {
        val instrument = anInstrument()
        val user = aUserWithShares(instrument, 10)
        val reference = aShareHold(user, instrument, 6)

        val result = fill(UUID.randomUUID(), reference, instrument, quantity = 4, amount = 20_000, fee = 200)

        assertEquals(201, result.response.status)
        assertEquals(Balance(booked = 6, reserved = 2, available = 4), shares(user, instrument))
        assertEquals(Balance(booked = 109_800, reserved = 0, available = 109_800), cash(user))
        assertEquals(HoldRow(amount = 6, consumed = 4, released = 0), holdRow(reference))
    }

    @Test
    fun `a sell is one transfer with the hold, and six entries that sum to zero for each asset`() {
        val instrument = anInstrument()
        val user = aUserWithShares(instrument, 10)
        val reference = aShareHold(user, instrument, 6)

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
            listOf("CASH/EUR", "POSITION/$instrument"),
            jdbc.sql(
                """
                select distinct a.asset_type || '/' || a.asset_code from ledger_entries e
                join transfers t on t.id = e.transfer_id
                join accounts a on a.id = e.account_id
                where t.uuid = :uuid order by 1
                """.trimIndent()
            ).param("uuid", transfer).query(String::class.java).list()
        )
    }

    @Test
    fun `the venue pays the price and takes the shares, and the broker gets the fee`() {
        val instrument = anInstrument()
        val user = aUserWithShares(instrument, 10)
        val reference = aShareHold(user, instrument, 6)
        val settlementBefore = house("VENUE_SETTLEMENT", "CASH", "EUR")
        val feeIncomeBefore = house("FEE_INCOME", "CASH", "EUR")
        assertEquals(-10, house("VENUE_SETTLEMENT", "POSITION", instrument))

        fill(UUID.randomUUID(), reference, instrument, quantity = 4, amount = 20_000, fee = 200)

        assertEquals(settlementBefore - 20_000, house("VENUE_SETTLEMENT", "CASH", "EUR"))
        assertEquals(feeIncomeBefore + 200, house("FEE_INCOME", "CASH", "EUR"))
        assertEquals(-6, house("VENUE_SETTLEMENT", "POSITION", instrument))
    }

    @Test
    fun `no cash account ever gets the instrument as its code`() {
        val instrument = anInstrument()
        val user = aUserWithShares(instrument, 10)
        val reference = aShareHold(user, instrument, 6)

        fill(UUID.randomUUID(), reference, instrument, quantity = 4, amount = 20_000, fee = 200)

        assertEquals(0, count("select count(*) from accounts where asset_type = 'CASH' and asset_code = :code", "code" to instrument))
    }

    @Test
    fun `a sell without a fee writes four entries and pays the whole price`() {
        val instrument = anInstrument()
        val user = aUserWithShares(instrument, 10)
        val reference = aShareHold(user, instrument, 6)

        val result = fill(UUID.randomUUID(), reference, instrument, quantity = 4, amount = 20_000, fee = 0)

        assertEquals(201, result.response.status)
        assertEquals(
            4,
            count(
                "select count(*) from ledger_entries e join transfers t on t.id = e.transfer_id where t.uuid = :uuid",
                "uuid" to transferUuid(result)
            )
        )
        assertEquals(Balance(booked = 110_000, reserved = 0, available = 110_000), cash(user))
    }

    @Test
    fun `an order sold in two parts consumes the share hold twice, and the release gives back the unsold shares`() {
        val instrument = anInstrument()
        val user = aUserWithShares(instrument, 10)
        val reference = aShareHold(user, instrument, 6)

        assertEquals(201, fill(UUID.randomUUID(), reference, instrument, quantity = 3, amount = 15_000, fee = 150).response.status)
        assertEquals(201, fill(UUID.randomUUID(), reference, instrument, quantity = 2, amount = 10_200, fee = 100).response.status)

        assertEquals(HoldRow(amount = 6, consumed = 5, released = 0), holdRow(reference))
        assertEquals(Balance(booked = 5, reserved = 1, available = 4), shares(user, instrument))

        assertEquals(200, release(reference))

        assertEquals(HoldRow(amount = 6, consumed = 5, released = 1), holdRow(reference))
        assertEquals(Balance(booked = 5, reserved = 0, available = 5), shares(user, instrument))
        assertEquals(Balance(booked = 114_950, reserved = 0, available = 114_950), cash(user))
    }

    @Test
    fun `the money from a sell can be withdrawn at once`() {
        val instrument = anInstrument()
        val user = aUserWithShares(instrument, 10)
        val reference = aShareHold(user, instrument, 10)
        fill(UUID.randomUUID(), reference, instrument, quantity = 10, amount = 50_000, fee = 500)

        assertEquals(201, money("/api/ledger/withdrawals", user, 139_500).response.status)

        assertEquals(Balance(booked = 0, reserved = 0, available = 0), cash(user))
        assertEquals(Balance(booked = 0, reserved = 0, available = 0), shares(user, instrument))
    }

    @Test
    fun `the same sell execution again answers 200 with the first transfer and books nothing more`() {
        val instrument = anInstrument()
        val user = aUserWithShares(instrument, 10)
        val reference = aShareHold(user, instrument, 6)
        val execution = UUID.randomUUID()

        val first = fill(execution, reference, instrument, quantity = 4, amount = 20_000, fee = 200)
        val second = fill(execution, reference, instrument, quantity = 4, amount = 20_000, fee = 200)

        assertEquals(201, first.response.status)
        assertEquals(200, second.response.status)
        assertEquals(transferUuid(first), transferUuid(second))
        assertEquals(Balance(booked = 6, reserved = 2, available = 4), shares(user, instrument))
        assertEquals(Balance(booked = 109_800, reserved = 0, available = 109_800), cash(user))
    }

    @Test
    fun `a sell of more shares than the hold has left is a 409 and writes nothing`() {
        val instrument = anInstrument()
        val user = aUserWithShares(instrument, 10)
        val reference = aShareHold(user, instrument, 6)
        val execution = UUID.randomUUID()

        val result = fill(execution, reference, instrument, quantity = 7, amount = 35_000, fee = 350)

        assertEquals(409, result.response.status)
        assertEquals(Balance(booked = 10, reserved = 6, available = 4), shares(user, instrument))
        assertEquals(Balance(booked = 90_000, reserved = 0, available = 90_000), cash(user))
        assertEquals(HoldRow(amount = 6, consumed = 0, released = 0), holdRow(reference))
        assertEquals(0, count("select count(*) from transfers where idempotency_key = :key", "key" to execution))
    }

    @Test
    fun `a sell that names another instrument than its hold is a 400 and writes nothing`() {
        val instrument = anInstrument()
        val user = aUserWithShares(instrument, 10)
        val reference = aShareHold(user, instrument, 6)
        val execution = UUID.randomUUID()

        val result = fill(execution, reference, anInstrument(), quantity = 4, amount = 20_000, fee = 200)

        assertEquals(400, result.response.status)
        assertEquals(Balance(booked = 10, reserved = 6, available = 4), shares(user, instrument))
        assertEquals(HoldRow(amount = 6, consumed = 0, released = 0), holdRow(reference))
        assertEquals(0, count("select count(*) from transfers where idempotency_key = :key", "key" to execution))
    }

    @Test
    fun `a fee larger than the price is a 400`() {
        val instrument = anInstrument()
        val user = aUserWithShares(instrument, 10)
        val reference = aShareHold(user, instrument, 6)

        assertEquals(400, fill(UUID.randomUUID(), reference, instrument, quantity = 4, amount = 200, fee = 201).response.status)
        assertEquals(Balance(booked = 10, reserved = 6, available = 4), shares(user, instrument))
    }

    // ---- at the same moment

    @Test
    fun `ten sells of one share on a hold of three at the same moment, exactly three are booked`() {
        val instrument = anInstrument()
        val user = aUserWithShares(instrument, 10)
        val reference = aShareHold(user, instrument, 3)
        val pool = Executors.newFixedThreadPool(10)
        val start = CountDownLatch(1)

        val statuses = (1..10).map {
            pool.submit<Int> {
                start.await()
                fill(UUID.randomUUID(), reference, instrument, quantity = 1, amount = 5_000, fee = 50).response.status
            }
        }
            .also { start.countDown() }
            .map { it.get(20, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(3, statuses.count { it == 201 }, "statuses: $statuses")
        assertEquals(7, statuses.count { it == 409 }, "statuses: $statuses")
        assertEquals(Balance(booked = 7, reserved = 0, available = 7), shares(user, instrument))
        assertEquals(Balance(booked = 104_850, reserved = 0, available = 104_850), cash(user))
        assertEquals(HoldRow(amount = 3, consumed = 3, released = 0), holdRow(reference))
    }

    @Test
    fun `ten share holds of three on ten shares at the same moment, exactly three succeed`() {
        val instrument = anInstrument()
        val user = aUserWithShares(instrument, 10)
        val pool = Executors.newFixedThreadPool(10)
        val start = CountDownLatch(1)

        val statuses = (1..10).map { pool.submit<Int> { start.await(); hold(UUID.randomUUID(), user, "POSITION", instrument, 3) } }
            .also { start.countDown() }
            .map { it.get(20, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(3, statuses.count { it == 201 }, "statuses: $statuses")
        assertEquals(7, statuses.count { it == 409 }, "statuses: $statuses")
        assertEquals(Balance(booked = 10, reserved = 9, available = 1), shares(user, instrument))
    }

    @Test
    fun `buys and sells for one user in one instrument at the same moment do not deadlock and keep the books exact`() {
        val instrument = anInstrument()
        val user = aUserWithShares(instrument, 10)
        val sell = aShareHold(user, instrument, 5)
        val buy = aHold(user, "CASH", "EUR", 5_000)
        val pool = Executors.newFixedThreadPool(10)
        val start = CountDownLatch(1)

        val statuses = (1..10).map { i ->
            pool.submit<Int> {
                start.await()
                if (i % 2 == 0) fill(UUID.randomUUID(), sell, instrument, quantity = 1, amount = 1_000, fee = 100).response.status
                else fill(UUID.randomUUID(), buy, instrument, quantity = 1, amount = 900, fee = 100).response.status
            }
        }
            .also { start.countDown() }
            .map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(List(10) { 201 }, statuses)
        assertEquals(Balance(booked = 10, reserved = 0, available = 10), shares(user, instrument))
        assertEquals(Balance(booked = 89_500, reserved = 0, available = 89_500), cash(user))
        assertEquals(-10, house("VENUE_SETTLEMENT", "POSITION", instrument))
        assertEquals(0, accountsWhereCopyDiffersFromEntries())
        assertEquals(0, assetsThatDoNotSumToZero())
    }

    @Test
    fun `one user sells to the venue while another buys the same instrument, and the venue's position is exact`() {
        val instrument = anInstrument()
        val seller = aUserWithShares(instrument, 10)
        val buyer = aUserWithShares(instrument, 10)
        val sell = aShareHold(seller, instrument, 10)
        val buy = aHold(buyer, "CASH", "EUR", 50_000)
        val pool = Executors.newFixedThreadPool(10)
        val start = CountDownLatch(1)

        val statuses = (1..20).map { i ->
            pool.submit<Int> {
                start.await()
                if (i % 2 == 0) fill(UUID.randomUUID(), sell, instrument, quantity = 1, amount = 5_000, fee = 0).response.status
                else fill(UUID.randomUUID(), buy, instrument, quantity = 1, amount = 5_000, fee = 0).response.status
            }
        }
            .also { start.countDown() }
            .map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(List(20) { 201 }, statuses)
        assertEquals(Balance(booked = 0, reserved = 0, available = 0), shares(seller, instrument))
        assertEquals(Balance(booked = 20, reserved = 0, available = 20), shares(buyer, instrument))
        assertEquals(-20, house("VENUE_SETTLEMENT", "POSITION", instrument))
        assertEquals(0, accountsWhereCopyDiffersFromEntries())
        assertEquals(0, assetsThatDoNotSumToZero())
    }
}
