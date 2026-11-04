package io.github.siloverse.loadgen

import java.time.Duration

/**
 * One workload, in requests per second. The baseline profile is the one from the
 * requirements interview and is reused unchanged for every comparison in the plan; a
 * second profile skews the orders onto one instrument for the engine capacity test.
 *
 * @property restingShare the share of orders priced so they do not fill; those are the
 *   ones cancels are sent against, so it equals the cancel ratio.
 * @property hotShare share of orders sent to the first instrument; null = uniform.
 * @property referencePrice the venue's start mid, in minor units; crossing and resting
 *   prices are set a margin above and below it.
 */
data class Profile(
    val name: String,
    val ordersPerSecond: Double,
    val cancelRatio: Double,
    val quotesPerSecond: Double,
    val portfolioReadsPerSecond: Double,
    val subscribers: Int,
    val instruments: List<String>,
    val hotShare: Double? = null,
    val referencePrice: Long = 100_00,
    val priceMarginPercent: Int = 10,
    val quantity: Long = 10,
) {
    val cancelsPerSecond: Double get() = ordersPerSecond * cancelRatio
    val restingShare: Double get() = cancelRatio

    fun rate(type: RequestType): Double = when (type) {
        RequestType.ORDER -> ordersPerSecond
        RequestType.CANCEL -> cancelsPerSecond
        RequestType.QUOTE -> quotesPerSecond
        RequestType.PORTFOLIO -> portfolioReadsPerSecond
        RequestType.FILL -> 0.0
    }

    fun scaled(factor: Double, name: String = "${this.name}×$factor"): Profile = copy(
        name = name,
        ordersPerSecond = ordersPerSecond * factor,
        quotesPerSecond = quotesPerSecond * factor,
        portfolioReadsPerSecond = portfolioReadsPerSecond * factor,
    )

    companion object {
        val TEN_INSTRUMENTS: List<String> = listOf("ACME", "BOLT", "CRUX", "DUNE", "ECHO", "FLUX", "GALE", "HALO", "IRIS", "JADE")

        /** The interview workload at the open: 40 orders/s, 20 % cancels, 200 quotes/s, 100 portfolio reads/s, 1,000 subscribers. */
        val BASELINE = Profile("baseline", ordersPerSecond = 40.0, cancelRatio = 0.2, quotesPerSecond = 200.0, portfolioReadsPerSecond = 100.0, subscribers = 1_000, instruments = TEN_INSTRUMENTS)

        /** Same rates, 90 % of orders on one instrument: the hot-partition run of the capacity test. */
        val SKEWED = BASELINE.copy(name = "skewed", hotShare = 0.9)

        fun named(name: String): Profile = when (name.lowercase()) {
            "baseline" -> BASELINE
            "skewed" -> SKEWED
            else -> throw IllegalArgumentException("unknown profile '$name'; known: baseline, skewed")
        }

        val DEFAULT_DURATION: Duration = Duration.ofSeconds(60)
    }
}
