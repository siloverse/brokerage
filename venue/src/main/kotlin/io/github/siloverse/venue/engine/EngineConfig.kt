package io.github.siloverse.venue.engine

import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId

/**
 * The knobs of the fake exchange. Stage C uses the first four; stage D adds the chaos
 * ones on top at runtime.
 *
 * @property fillDelay how long a crossing order waits before its first fill.
 * @property partialFills 1 = one fill for the whole quantity, 2 = two fills of half each.
 * @property startPrice the mid price every instrument starts at, in minor units.
 * @property priceStep the largest move of the random walk per tick, in minor units; 0 pins the price.
 * @property sessionClose the daily close in [zone]; resting orders expire at that instant.
 */
data class EngineConfig(
    val fillDelay: Duration = Duration.ZERO,
    val partialFills: Int = 1,
    val startPrice: Long = 100_00,
    val priceStep: Long = 5,
    val sessionClose: LocalTime = LocalTime.of(17, 30),
    val zone: ZoneId = ZoneId.of("Europe/Berlin"),
    val seed: Long = 42,
) {
    init {
        require(partialFills in 1..2) { "partialFills must be 1 or 2" }
        require(priceStep >= 0 && startPrice > 0)
    }
}
