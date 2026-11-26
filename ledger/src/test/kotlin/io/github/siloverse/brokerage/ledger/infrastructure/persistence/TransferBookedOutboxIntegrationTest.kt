package io.github.siloverse.brokerage.ledger.infrastructure.persistence

import io.github.siloverse.brokerage.ledger.BaseIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.util.UUID

/** A booked transfer leaves exactly one `TransferBooked` row in the outbox, in its own transaction. */
class TransferBookedOutboxIntegrationTest : BaseIntegrationTest() {

    private val user = UUID.randomUUID()

    private fun deposit(amount: Long, key: UUID = UUID.randomUUID()): Pair<Int, String> = send("deposits", amount, key)

    private fun withdraw(amount: Long, key: UUID = UUID.randomUUID()): Pair<Int, String> = send("withdrawals", amount, key)

    private fun send(path: String, amount: Long, key: UUID): Pair<Int, String> {
        val response = mvc.perform(
            post("/api/ledger/$path")
                .header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userUuid":"$user","currency":"EUR","amount":$amount}""")
        ).andReturn().response
        return response.status to response.contentAsString
    }

    private fun transferUuidIn(body: String): String = Regex("\"transferUuid\":\"([^\"]+)\"").find(body)!!.groupValues[1]

    private fun outboxRowsFor(transferUuid: String): List<Map<String, Any?>> =
        jdbc.sql(
            """
                select message_type,
                       convert_from(payload, 'UTF8')::jsonb ->> 'kind' as kind,
                       published_at
                from messaging_outbox
                where convert_from(payload, 'UTF8')::jsonb ->> 'transferUuid' = :transfer_uuid
            """.trimIndent()
        ).param("transfer_uuid", transferUuid).query().listOfRows()

    private fun transfersOf(user: UUID): Long =
        jdbc.sql(
            """
                select count(distinct e.transfer_id) from ledger_entries e
                join accounts a on a.id = e.account_id where a.user_uuid = :user
            """.trimIndent()
        ).param("user", user).query(Long::class.java).single()

    private fun outboxRows(): Long = jdbc.sql("select count(*) from messaging_outbox").query(Long::class.java).single()

    @Test
    fun `a deposit leaves one unpublished row with the wire name, the transfer and its kind`() {
        val (status, body) = deposit(10_000)
        assertEquals(201, status)

        val rows = outboxRowsFor(transferUuidIn(body))

        assertEquals(1, rows.size)
        assertEquals("ledger.transfer-booked", rows.single()["message_type"])
        assertEquals("DEPOSIT", rows.single()["kind"])
        assertNull(rows.single()["published_at"], "nothing relays in this test, so the row is still waiting")
    }

    @Test
    fun `a withdrawal is announced with its own kind`() {
        deposit(10_000)
        val (status, body) = withdraw(4_000)
        assertEquals(201, status)

        assertEquals(listOf("WITHDRAWAL"), outboxRowsFor(transferUuidIn(body)).map { it["kind"] })
    }

    @Test
    fun `the same key sent twice announces the transfer once`() {
        val key = UUID.randomUUID()
        val (first, firstBody) = deposit(10_000, key)
        val (second, secondBody) = deposit(10_000, key)
        assertEquals(201, first)
        assertEquals(200, second)
        assertEquals(transferUuidIn(firstBody), transferUuidIn(secondBody))

        assertEquals(1, outboxRowsFor(transferUuidIn(firstBody)).size)
    }

    @Test
    fun `a refused withdrawal leaves no transfer and no row`() {
        deposit(1_000)
        val transfersBefore = transfersOf(user)
        val rowsBefore = outboxRows()

        val (status, _) = withdraw(5_000)

        assertEquals(409, status)
        assertEquals(transfersBefore, transfersOf(user))
        assertEquals(rowsBefore, outboxRows())
    }
}
