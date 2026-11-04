package io.github.siloverse.venue.fix

import io.github.siloverse.brokerage.orders.Venue
import io.github.siloverse.brokerage.orders.VenueContract
import io.github.siloverse.brokerage.orders.venue.fix.FixVenue
import io.github.siloverse.brokerage.orders.venue.fix.FixVenueConfig
import io.github.siloverse.fix.session.SessionConfig
import io.github.siloverse.fix.session.SessionRole
import io.github.siloverse.fix.store.InMemoryMessageStore
import io.github.siloverse.venue.engine.EngineConfig
import io.github.siloverse.venue.engine.VenueEngine
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * The Venue port's contract, run against the real thing: the venue's FIX acceptor and the
 * broker's FIX adapter over a TCP socket on a free port. The price is pinned at the
 * contract's limit so every order crosses and fills at once.
 */
class FixVenueContractTest : VenueContract() {

    private lateinit var server: VenueFixServer
    private lateinit var venue: FixVenue

    @BeforeEach
    fun startVenue() {
        val engine = VenueEngine(EngineConfig(priceStep = 0, startPrice = 10_00, sessionClose = LocalTime.MAX, zone = ZoneOffset.UTC))
        server = VenueFixServer(
            engine,
            SessionConfig("VENUE", "BROKER", SessionRole.ACCEPTOR, Duration.ofSeconds(5)),
            InMemoryMessageStore(),
            fixPort = 0,
            tickInterval = Duration.ofMillis(20),
        )
        server.start()
        venue = FixVenue(
            FixVenueConfig(host = "127.0.0.1", port = server.port, heartBtInt = Duration.ofSeconds(5), reconnectBackoff = Duration.ofMillis(100)),
            InMemoryMessageStore(),
        )
        venue.start()
    }

    @AfterEach
    fun stopVenue() {
        venue.close()
        server.stop()
    }

    override fun venue(): Venue = venue
}
