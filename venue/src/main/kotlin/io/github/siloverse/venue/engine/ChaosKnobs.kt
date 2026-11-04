package io.github.siloverse.venue.engine

import java.time.Duration

/**
 * The venue's misbehaviour, changeable at runtime through the admin endpoint. Read by the
 * engine on every decision, so a change applies to the next order or tick. Rates are
 * probabilities in `[0, 1]`.
 */
class ChaosKnobs(
    @Volatile var fillDelay: Duration = Duration.ZERO,
    @Volatile var partialFills: Int = 1,
    /** Reject this share of new orders with ExecType=8. */
    @Volatile var rejectRate: Double = 0.0,
    /** Send this share of Trade reports twice: same ExecID, new sequence number. Guarantees 2 and 4. */
    @Volatile var duplicateReportRate: Double = 0.0,
    /** Per tick, send a Trade for this share of already-filled orders: a fill the broker must refuse. */
    @Volatile var phantomReportRate: Double = 0.0,
) {
    fun validate() {
        require(partialFills in 1..2) { "partialFills must be 1 or 2" }
        require(!fillDelay.isNegative) { "fillDelay must not be negative" }
        for ((name, rate) in listOf("rejectRate" to rejectRate, "duplicateReportRate" to duplicateReportRate, "phantomReportRate" to phantomReportRate)) {
            require(rate in 0.0..1.0) { "$name must be between 0 and 1" }
        }
    }

    fun snapshot(): Map<String, Any> = mapOf(
        "fillDelay" to fillDelay.toString(),
        "partialFills" to partialFills,
        "rejectRate" to rejectRate,
        "duplicateReportRate" to duplicateReportRate,
        "phantomReportRate" to phantomReportRate,
    )
}
