package io.github.siloverse.loadgen

import io.github.siloverse.brokerage.orders.application.venue.NewOrder
import io.github.siloverse.brokerage.orders.domain.ClientOrderId
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class OpenLoopSchedulerTest {

    private class SlowTarget(val delayMillis: Long) : Target {
        val submits = AtomicInteger()
        override val description = "fake"
        override val supports = setOf(RequestType.ORDER)
        override suspend fun submit(order: NewOrder): Outcome { submits.incrementAndGet(); delay(delayMillis); return Outcome.OK }
        override suspend fun cancel(id: ClientOrderId) = Outcome.OK
        override fun close() {}
    }

    @Test
    fun `sends at the profile rate regardless of how slow the target answers`() = runBlocking {
        val target = SlowTarget(delayMillis = 400)   // a closed loop could manage 2.5/s at most
        val profile = Profile.BASELINE.copy(ordersPerSecond = 50.0, cancelRatio = 0.0, quotesPerSecond = 0.0, portfolioReadsPerSecond = 0.0)
        val report = OpenLoopScheduler(profile, target).run(Duration.ofSeconds(2))
        val orders = report.stats.single { it.type == RequestType.ORDER }
        assertTrue(target.submits.get() in 90..105, "open loop should send ~100 in 2 s, sent ${target.submits.get()}")
        assertEquals(orders.sent, orders.ok)
        assertTrue(orders.p50Micros >= 400_000, "latency includes the target's 400 ms: p50 ${orders.p50Micros}")
    }

    @Test
    fun `types the target cannot serve are reported as not run, not as numbers`() = runBlocking {
        val report = OpenLoopScheduler(Profile.BASELINE.copy(ordersPerSecond = 5.0), SlowTarget(0)).run(Duration.ofMillis(300))
        assertEquals(listOf(RequestType.CANCEL, RequestType.QUOTE, RequestType.PORTFOLIO), report.notRun)
        assertTrue(report.markdown().contains("Not run (no target yet): cancel, quote, portfolio"))
    }
}
