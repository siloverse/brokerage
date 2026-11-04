package io.github.siloverse.loadgen

import org.HdrHistogram.ConcurrentHistogram
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Latency histograms and outcome counters, one set per request type. HdrHistogram keeps
 * every value at three significant digits, so p99 and max are exact enough to argue about
 * and the memory stays small whatever the rate.
 *
 * Latency is recorded only for [Outcome.OK]; every other outcome is counted. Values are
 * microseconds.
 */
class LatencyRecorder {

    private class Slot {
        val histogram = ConcurrentHistogram(3).apply { setAutoResize(true) }
        val counts = ConcurrentHashMap<Outcome, AtomicLong>()
        fun count(outcome: Outcome) = counts.computeIfAbsent(outcome) { AtomicLong() }.incrementAndGet()
    }

    private val slots = ConcurrentHashMap<RequestType, Slot>()

    private fun slot(type: RequestType) = slots.computeIfAbsent(type) { Slot() }

    fun record(type: RequestType, outcome: Outcome, latencyMicros: Long) {
        val slot = slot(type)
        slot.count(outcome)
        if (outcome == Outcome.OK) slot.histogram.recordValue(latencyMicros.coerceAtLeast(1))
    }

    fun snapshot(type: RequestType): TypeStats? {
        val slot = slots[type] ?: return null
        val h = slot.histogram.copy()
        fun count(o: Outcome) = slot.counts[o]?.get() ?: 0L
        return TypeStats(
            type = type,
            ok = count(Outcome.OK), rejected = count(Outcome.REJECTED), timedOut = count(Outcome.TIMEOUT),
            errors = count(Outcome.ERROR), skipped = count(Outcome.SKIPPED),
            p50Micros = h.getValueAtPercentile(50.0), p90Micros = h.getValueAtPercentile(90.0),
            p99Micros = h.getValueAtPercentile(99.0), maxMicros = h.maxValue,
        )
    }

    fun snapshot(): List<TypeStats> = RequestType.entries.mapNotNull(::snapshot)
}

data class TypeStats(
    val type: RequestType,
    val ok: Long, val rejected: Long, val timedOut: Long, val errors: Long, val skipped: Long,
    val p50Micros: Long, val p90Micros: Long, val p99Micros: Long, val maxMicros: Long,
) {
    /** Requests actually sent: everything except the skipped ones. */
    val sent: Long get() = ok + rejected + timedOut + errors
}
