package io.github.siloverse.loadgen

import io.github.siloverse.brokerage.orders.application.venue.CancelOrder
import io.github.siloverse.brokerage.orders.application.venue.ExecutionReport
import io.github.siloverse.brokerage.orders.application.venue.NewOrder
import io.github.siloverse.brokerage.orders.application.venue.VenueUnavailableException
import io.github.siloverse.brokerage.orders.domain.ClientOrderId
import io.github.siloverse.brokerage.orders.domain.VenueOrderId
import io.github.siloverse.brokerage.orders.infrastructure.fix.FixVenue
import io.github.siloverse.brokerage.orders.infrastructure.fix.FixVenueConfig
import io.github.siloverse.fix.store.InMemoryMessageStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * The fake venue over FIX, through the broker's own adapter: the first target, available
 * since 0.3b. Submit latency is the time to the venue's receipt (the New report), the
 * plan's "acknowledgement". A cancel waits for the Canceled report or the reject.
 */
class FixVenueTarget(host: String, port: Int, replyTimeout: Duration = Duration.ofSeconds(2)) : Target {

    private val venue = FixVenue(FixVenueConfig(host = host, port = port, replyTimeout = replyTimeout, reconnectBackoff = Duration.ofMillis(200), resetOnLogon = true), InMemoryMessageStore())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val venueIds = ConcurrentHashMap<ClientOrderId, VenueOrderId>()
    private val awaitingFirstReport = ConcurrentHashMap<ClientOrderId, CompletableDeferred<Outcome>>()
    private val awaitingCancel = ConcurrentHashMap<ClientOrderId, CompletableDeferred<Outcome>>()
    private val replyTimeoutMillis = replyTimeout.toMillis()

    override val description = "fake venue over FIX at $host:$port via orders.infrastructure.fix.FixVenue"
    override val supports = setOf(RequestType.ORDER, RequestType.CANCEL)

    init {
        venue.start()
        // The adapter's submit() returns an id for a Rejected report too (the venue did answer).
        // The generator wants the business outcome, so it reads the first report itself.
        venue.executions().onEach { report ->
            when (report) {
                is ExecutionReport.New, is ExecutionReport.Trade -> awaitingFirstReport.remove(report.clientOrderId)?.complete(Outcome.OK)
                is ExecutionReport.Rejected -> awaitingFirstReport.remove(report.clientOrderId)?.complete(Outcome.REJECTED)
                is ExecutionReport.Canceled -> awaitingCancel.remove(report.clientOrderId)?.complete(Outcome.OK)
                is ExecutionReport.CancelRejected -> awaitingCancel.remove(report.clientOrderId)?.complete(Outcome.REJECTED)
                is ExecutionReport.Expired -> {}
            }
        }.launchIn(scope)
    }

    /** Block until the session is logged on, or fail. */
    fun awaitConnected(timeout: Duration = Duration.ofSeconds(10)) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!venue.isConnected) {
            check(System.nanoTime() < deadline) { "venue not reachable within $timeout" }
            Thread.sleep(20)
        }
    }

    /**
     * The fill row: OK = time from scheduled submit to the first fill; REJECTED = the venue
     * ended the order unfilled (Expired or Rejected), which is how a run outside the trading
     * session shows itself.
     */
    override fun watchFills(recorder: LatencyRecorder, sentAtNanos: (ClientOrderId) -> Long?) {
        venue.executions().onEach { report ->
            val sent = sentAtNanos(report.clientOrderId) ?: return@onEach
            when (report) {
                // first fill only: a second partial would double count
                is ExecutionReport.Trade -> if (report.cumQty == report.lastQty) recorder.record(RequestType.FILL, Outcome.OK, (System.nanoTime() - sent) / 1_000)
                is ExecutionReport.Expired, is ExecutionReport.Rejected -> recorder.record(RequestType.FILL, Outcome.REJECTED, 0)
                else -> {}
            }
        }.launchIn(scope)
    }

    override suspend fun submit(order: NewOrder): Outcome {
        val first = awaitingFirstReport.computeIfAbsent(order.clientOrderId) { CompletableDeferred() }
        return try {
            venueIds[order.clientOrderId] = venue.submit(order)
            withTimeoutOrNull(replyTimeoutMillis) { first.await() } ?: Outcome.TIMEOUT
        } catch (e: VenueUnavailableException) {
            Outcome.TIMEOUT
        } finally {
            awaitingFirstReport.remove(order.clientOrderId, first)
        }
    }

    override suspend fun cancel(id: ClientOrderId): Outcome {
        val answer = awaitingCancel.computeIfAbsent(id) { CompletableDeferred() }
        return try {
            venue.cancel(CancelOrder(id, venueIds[id]))
            withTimeoutOrNull(replyTimeoutMillis) { answer.await() } ?: Outcome.TIMEOUT
        } catch (e: VenueUnavailableException) {
            Outcome.TIMEOUT
        } finally {
            awaitingCancel.remove(id, answer)
        }
    }

    override fun close() {
        scope.cancel()
        venue.close()
    }
}
