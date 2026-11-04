package io.github.siloverse.brokerage.orders.application

import io.github.siloverse.brokerage.orders.domain.ClientOrderId
import io.github.siloverse.brokerage.orders.domain.VenueOrderId
import io.github.siloverse.brokerage.orders.domain.ExecutionId
import io.github.siloverse.brokerage.orders.domain.Instrument
import kotlinx.coroutines.flow.Flow

/**
 * The broker's outbound port to an execution venue: the fake FIX venue (0.3b) or our own
 * matching engine (chapter 3). The `orders` module is the only caller and must never know
 * which implementation it got. No transport detail belongs here.
 *
 * ## Contract — every implementation is tested against these with [VenueContract]
 *
 * 1. **`submit` is idempotent on [ClientOrderId].** A retry with the same id after a
 *    timeout returns the same [VenueOrderId] and creates no second order.
 * 2. **Execution ids are assigned by the venue, unique per venue, and never change.** The
 *    broker deduplicates reports by [ExecutionId], never by delivery.
 * 3. **Reports arrive in session sequence order with no gaps** from any single subscription
 *    to [executions]. Reports for one order are therefore ordered. Across orders no further
 *    promise is made.
 * 4. **Delivery is at least once.** A consumer may see the same report twice and must be
 *    safe when it does.
 * 5. **[lookup] is authoritative.** It returns the venue's current view of the order with
 *    every execution so far, or `null` if the venue never received a submit with that id.
 *    The unknown-state sweeper (2.6) and the daily comparison (2.7) rely on it.
 * 6. **The venue owns the disposition of the unfilled remainder.** Only a `Canceled`,
 *    `Expired` or `Rejected` report may release a hold. The venue enforces the trading
 *    session; the broker never infers expiry from its own clock.
 *
 * ## Not promised
 * No latency bound, no delivery deadline, no ordering across orders, and `submit`
 * returning does **not** mean the order is accepted: acceptance is the `New` report.
 */
interface Venue {

    /** Hands the order to the venue. Returns a receipt: the venue's own id for it. */
    suspend fun submit(order: NewOrder): VenueOrderId

    /** Asks the venue to cancel the unfilled remainder. The answer is a report, never a return value. */
    suspend fun cancel(request: CancelOrder)

    /** The venue's authoritative view of an order, or `null` if it never received it. */
    suspend fun lookup(id: ClientOrderId): VenueOrderView?

    /** Cold stream of execution reports in session sequence order. One subscription per consumer. */
    fun executions(): Flow<ExecutionReport>

    /** Cold stream of best bid and ask for one instrument. */
    fun quotes(instrument: Instrument): Flow<TopOfBook>
}
