package io.github.siloverse.loadgen

import io.github.siloverse.fix.session.SessionConfig
import io.github.siloverse.fix.session.SessionRole
import io.github.siloverse.fix.store.InMemoryMessageStore
import io.github.siloverse.venue.engine.EngineConfig
import io.github.siloverse.venue.engine.VenueEngine
import io.github.siloverse.venue.fix.VenueFixServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneOffset

/** The whole chain in one process: scheduler → FixVenue adapter → TCP → venue acceptor → engine. */
class FixVenueTargetTest {

    private lateinit var server: VenueFixServer
    private lateinit var target: FixVenueTarget

    @BeforeEach
    fun start() {
        val engine = VenueEngine(EngineConfig(priceStep = 0, startPrice = 100_00, sessionClose = LocalTime.MAX, zone = ZoneOffset.UTC))
        server = VenueFixServer(engine, SessionConfig("VENUE", "BROKER", SessionRole.ACCEPTOR, Duration.ofSeconds(5)), InMemoryMessageStore(), fixPort = 0, tickInterval = Duration.ofMillis(20))
        server.start()
        target = FixVenueTarget("127.0.0.1", server.port)
        target.awaitConnected()
    }

    @AfterEach
    fun stop() { target.close(); server.stop() }

    @Test
    fun `a short baseline run against the fake venue`() = runBlocking {
        val profile = Profile.BASELINE.copy(ordersPerSecond = 20.0, quotesPerSecond = 0.0, portfolioReadsPerSecond = 0.0)
        val report = OpenLoopScheduler(profile, target).run(Duration.ofSeconds(3))
        println(report.markdown())
        val orders = report.stats.single { it.type == RequestType.ORDER }
        val cancels = report.stats.single { it.type == RequestType.CANCEL }
        val fills = report.stats.single { it.type == RequestType.FILL }
        assertTrue(orders.sent in 55..62, "orders ${orders.sent}")
        assertEquals(orders.sent, orders.ok, "every submit is acknowledged")
        assertTrue(cancels.ok >= 1 && cancels.rejected == 0L, "resting orders cancel cleanly: $cancels")
        assertTrue(fills.ok >= orders.ok * 0.6, "crossing orders fill: ${fills.ok} of ${orders.ok}")
        assertTrue(orders.p99Micros < 500_000, "p99 ${orders.p99Micros} µs")
    }
}
