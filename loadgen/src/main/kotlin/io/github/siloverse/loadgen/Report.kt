package io.github.siloverse.loadgen

import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** One run's result, printed as the markdown table that goes into docs/numbers.md. */
data class Report(
    val profile: Profile,
    val target: String,
    val requested: Duration,
    val elapsed: Duration,
    val stats: List<TypeStats>,
    val notRun: List<RequestType>,
    val at: LocalDateTime = LocalDateTime.now(),
) {
    /** The pass marks from docs/numbers.md section 6, in microseconds. */
    private fun passMarkMicros(type: RequestType): Long? = when (type) {
        RequestType.ORDER -> 100_000
        RequestType.QUOTE -> 50_000
        else -> null
    }

    fun markdown(): String = buildString {
        appendLine("### loadgen ${profile.name} — ${at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}")
        appendLine()
        appendLine("Target: $target. Requested ${requested.seconds} s, ran ${elapsed.toMillis() / 1000.0} s. Open loop; latency from the scheduled send time; only OK requests count toward percentiles. Skipped = scheduled but nothing to send (a cancel with no resting order).")
        appendLine()
        appendLine("| type | target rate | sent | achieved rate | ok | rejected | timeout | error | skipped | p50 | p90 | p99 | max | pass mark | verdict |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        for (s in stats) {
            val seconds = requested.toMillis() / 1000.0
            val rate = profile.rate(s.type)
            val targetRate = if (s.type == RequestType.FILL) "—" else "%.1f/s".format(rate)
            val achieved = if (s.type == RequestType.FILL) "—" else "%.1f/s".format(s.sent / seconds)
            val mark = passMarkMicros(s.type)
            val verdict = when {
                mark == null -> "—"
                s.ok == 0L -> "no data"
                s.p99Micros <= mark -> "PASS"
                else -> "FAIL"
            }
            appendLine("| ${s.type.name.lowercase()} | $targetRate | ${s.sent} | $achieved | ${s.ok} | ${s.rejected} | ${s.timedOut} | ${s.errors} | ${s.skipped} | ${ms(s.p50Micros)} | ${ms(s.p90Micros)} | ${ms(s.p99Micros)} | ${ms(s.maxMicros)} | ${mark?.let(::ms) ?: "—"} | $verdict |")
        }
        if (notRun.isNotEmpty()) {
            appendLine()
            appendLine("Not run (no target yet): ${notRun.joinToString { it.name.lowercase() }}.")
        }
    }

    private fun ms(micros: Long) = "%.1f ms".format(micros / 1000.0)
}
