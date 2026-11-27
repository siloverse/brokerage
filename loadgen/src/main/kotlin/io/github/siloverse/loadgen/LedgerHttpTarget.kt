package io.github.siloverse.loadgen

import io.github.siloverse.brokerage.orders.application.venue.NewOrder
import io.github.siloverse.brokerage.orders.domain.ClientOrderId
import kotlinx.coroutines.future.await
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.UUID

/**
 * The ledger over HTTP, as the order flow uses it: placing an order is a **hold** on the
 * buyer's cash, cancelling it is a **release**. So the baseline profile runs unchanged:
 * its order rate is the hold rate and its cancel rate is the release rate.
 *
 * Every order holds cash (price × quantity), whatever its side: the users of a run are
 * given money, not shares. A hold's reference is derived from the order id, so a release
 * needs no lookup.
 *
 * @param users how many accounts the holds are spread over. Two holds on one account queue
 *   on that account's row, so a small number is a contention test, not a baseline.
 */
class LedgerHttpTarget(
    private val baseUrl: String,
    users: Int = 100,
    private val replyTimeout: Duration = Duration.ofSeconds(2),
    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build(),
) : Target {

    private val userUuids: List<UUID> = List(users) { UUID.randomUUID() }

    override val description = "ledger over HTTP at $baseUrl (order = hold, cancel = release), $users users"
    override val supports = setOf(RequestType.ORDER, RequestType.CANCEL)

    /** Give every user of this run enough cash that no hold fails for lack of money. */
    fun fundUsers(amount: Long = 1_000_000_000_000) {
        userUuids.forEach { user ->
            val request = post("/api/ledger/deposits", """{"userUuid":"$user","currency":"EUR","amount":$amount}""")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .timeout(Duration.ofSeconds(10))
                .build()
            val status = http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()
            check(status == 201) { "could not fund user $user at $baseUrl: http $status" }
        }
    }

    override suspend fun submit(order: NewOrder): Outcome {
        val amount = order.limitPrice.minorUnits * order.quantity.shares
        val body = """{"reference":"${referenceOf(order.clientOrderId)}","userUuid":"${userOf(order.clientOrderId)}","assetType":"CASH","assetCode":"EUR","amount":$amount}"""
        return send(post("/api/ledger/holds", body))
    }

    override suspend fun cancel(id: ClientOrderId): Outcome =
        send(post("/api/ledger/holds/${referenceOf(id)}/release", ""))

    override fun close() {
        http.close()
    }

    private suspend fun send(request: HttpRequest.Builder): Outcome =
        try {
            val response = http.sendAsync(request.timeout(replyTimeout).build(), HttpResponse.BodyHandlers.discarding()).await()
            outcomeOf(response.statusCode())
        } catch (e: HttpTimeoutException) {
            Outcome.TIMEOUT
        }

    private fun post(path: String, body: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))

    private fun referenceOf(id: ClientOrderId): UUID = UUID.nameUUIDFromBytes(id.value.toByteArray())

    private fun userOf(id: ClientOrderId): UUID = userUuids[Math.floorMod(id.value.hashCode(), userUuids.size)]

    companion object {
        /** 2xx = done; 4xx = the ledger said no (not enough money, unknown hold); 5xx = the ledger failed or was busy. */
        fun outcomeOf(status: Int): Outcome = when (status) {
            in 200..299 -> Outcome.OK
            in 400..499 -> Outcome.REJECTED
            else -> Outcome.ERROR
        }
    }
}
