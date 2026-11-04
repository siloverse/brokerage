package io.github.siloverse.venue.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId

/** `venue.*` in application.yml. */
@ConfigurationProperties(prefix = "venue")
data class VenueProperties(
    val fixPort: Int = 10402,
    val senderCompId: String = "VENUE",
    val targetCompId: String = "BROKER",
    val heartBtInt: Duration = Duration.ofSeconds(30),
    val storeDir: Path = Path.of("build/venue-fix-store"),
    val engine: Engine = Engine(),
) {
    data class Engine(
        val fillDelay: Duration = Duration.ZERO,
        val partialFills: Int = 1,
        val startPrice: Long = 100_00,
        val priceStep: Long = 5,
        val sessionClose: LocalTime = LocalTime.of(17, 30),
        val zone: ZoneId = ZoneId.of("Europe/Berlin"),
    )
}
