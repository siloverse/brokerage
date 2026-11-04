package io.github.siloverse.loadgen

import io.github.siloverse.brokerage.orders.domain.ClientOrderId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Sends each request type at its profile rate on a fixed timetable, **open loop**: the
 * next request goes out when the timetable says so, whether or not the previous one has
 * been answered. A closed loop (send, wait, send) would slow down exactly when the system
 * slows down and hide the queueing it causes; that mistake has a name, coordinated
 * omission. Latency is measured from the *scheduled* send time, so a generator that falls
 * behind charges the delay to the system, not to itself.
 */
class OpenLoopScheduler(
    private val profile: Profile,
    private val target: Target,
    private val recorder: LatencyRecorder = LatencyRecorder(),
    private val generator: OrderGenerator = OrderGenerator(profile),
    private val drain: Duration = Duration.ofSeconds(3),
) {
    private val sentAt = ConcurrentHashMap<ClientOrderId, Long>()

    /** Request types in the profile that this target cannot serve; reported, not run. */
    val notRun: List<RequestType> = RequestType.entries.filter { profile.rate(it) > 0 && it !in target.supports }

    suspend fun run(duration: Duration): Report {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val start = System.nanoTime()
        val end = start + duration.toNanos()
        target.watchFills(recorder) { id -> sentAt[id] }
        val streams = RequestType.entries
            .filter { it in target.supports && profile.rate(it) > 0 }
            .map { type -> scope.launch { stream(type, start, end, scope) } }
        streams.joinAll()
        // Let the in-flight requests finish; anything slower than the drain window is a timeout by definition.
        withTimeoutOrNull(drain.toMillis()) { scope.coroutineContext[Job]!!.children.toList().joinAll() }
        scope.cancel()
        val elapsed = Duration.ofNanos(System.nanoTime() - start)
        return Report(profile, target.description, duration, elapsed, recorder.snapshot(), notRun)
    }

    private suspend fun stream(type: RequestType, start: Long, end: Long, scope: CoroutineScope) {
        val intervalNanos = (1_000_000_000.0 / profile.rate(type)).toLong()
        var i = 0L
        while (true) {
            val due = start + i * intervalNanos
            if (due >= end) return
            val wait = due - System.nanoTime()
            if (wait > 0) delay(wait / 1_000_000)
            scope.launch { fire(type, due) }
            i++
        }
    }

    private suspend fun fire(type: RequestType, dueNanos: Long) {
        val outcome = try {
            when (type) {
                RequestType.ORDER -> {
                    val order = generator.next()
                    sentAt[order.clientOrderId] = dueNanos
                    target.submit(order)
                }
                RequestType.CANCEL -> generator.nextToCancel()?.let { target.cancel(it) } ?: Outcome.SKIPPED
                RequestType.QUOTE -> target.quote(profile.instruments.random())
                RequestType.PORTFOLIO -> target.portfolio("acct-${(1..100).random()}")
                RequestType.FILL -> Outcome.SKIPPED
            }
        } catch (e: Exception) {
            Outcome.ERROR
        }
        recorder.record(type, outcome, (System.nanoTime() - dueNanos) / 1_000)
    }
}
