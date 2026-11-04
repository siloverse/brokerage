package io.github.siloverse.brokerage.orders.application

import io.github.siloverse.brokerage.orders.domain.Price
import io.github.siloverse.brokerage.orders.domain.Quantity
import io.github.siloverse.brokerage.orders.domain.ClientOrderId
import io.github.siloverse.brokerage.orders.domain.VenueOrderId
import io.github.siloverse.brokerage.orders.domain.ExecutionId
import io.github.siloverse.brokerage.orders.domain.SequenceNumber
import java.time.Instant

/**
 * One fact from the venue. Names follow FIX ExecType so the FIX adapter is a straight
 * mapping. Lifecycle facts the broker learns on its own (`pending_submission`,
 * `submission_unknown`, `cancel_pending`, `cancel_unknown`, and `filled`, which is
 * derived from `leavesQty == 0`) have no report type on purpose.
 */
sealed interface ExecutionReport {
    val clientOrderId: ClientOrderId
    val venueOrderId: VenueOrderId
    val sequence: SequenceNumber
    val venueTime: Instant

    /** The venue accepted the order into its book. FIX ExecType=0. */
    data class New(
        override val clientOrderId: ClientOrderId,
        override val venueOrderId: VenueOrderId,
        override val sequence: SequenceNumber,
        override val venueTime: Instant,
    ) : ExecutionReport

    /** The venue refused the submit. Creates no order on the venue. FIX ExecType=8. */
    data class Rejected(
        override val clientOrderId: ClientOrderId,
        override val venueOrderId: VenueOrderId,
        override val sequence: SequenceNumber,
        override val venueTime: Instant,
        val reason: String,
    ) : ExecutionReport

    /** A fill, partial or last. The broker derives `filled` from [leavesQty] == 0. FIX ExecType=F. */
    data class Trade(
        override val clientOrderId: ClientOrderId,
        override val venueOrderId: VenueOrderId,
        override val sequence: SequenceNumber,
        override val venueTime: Instant,
        val executionId: ExecutionId,
        val lastQty: Quantity,
        val lastPrice: Price,
        val cumQty: Quantity,
        val leavesQty: Quantity,
    ) : ExecutionReport

    /** The venue cancelled the remainder on our request. FIX ExecType=4. */
    data class Canceled(
        override val clientOrderId: ClientOrderId,
        override val venueOrderId: VenueOrderId,
        override val sequence: SequenceNumber,
        override val venueTime: Instant,
        val cumQty: Quantity,
    ) : ExecutionReport

    /** The venue expired the remainder at session close. FIX ExecType=C. */
    data class Expired(
        override val clientOrderId: ClientOrderId,
        override val venueOrderId: VenueOrderId,
        override val sequence: SequenceNumber,
        override val venueTime: Instant,
        val cumQty: Quantity,
    ) : ExecutionReport

    /** A cancel could not be honoured, e.g. the order was already done. FIX OrderCancelReject (35=9). */
    data class CancelRejected(
        override val clientOrderId: ClientOrderId,
        override val venueOrderId: VenueOrderId,
        override val sequence: SequenceNumber,
        override val venueTime: Instant,
        val reason: String,
    ) : ExecutionReport
}
