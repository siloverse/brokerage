package io.github.siloverse.loadgen

import io.github.siloverse.brokerage.orders.application.venue.NewOrder
import io.github.siloverse.brokerage.orders.domain.ClientOrderId

/**
 * The system under test, seen from the generator. One implementation per entry point:
 * the FIX venue through the broker's adapter today, the broker's HTTP API from chapter 2,
 * the WebSocket feed from chapter 4. A target says which request types it serves; the
 * scheduler reports the rest as "no target yet" instead of inventing numbers.
 */
interface Target : AutoCloseable {
    val description: String
    val supports: Set<RequestType>

    /** Send one order. Returns when the system has acknowledged it (or not). */
    suspend fun submit(order: NewOrder): Outcome

    /** Cancel a resting order. Returns when the cancel is confirmed or refused. */
    suspend fun cancel(id: ClientOrderId): Outcome

    suspend fun quote(instrument: String): Outcome = Outcome.SKIPPED

    suspend fun portfolio(account: String): Outcome = Outcome.SKIPPED

    /** Called once per order after a successful submit, so a target can report fills to the recorder. */
    fun watchFills(recorder: LatencyRecorder, sentAtNanos: (ClientOrderId) -> Long?) {}
}
