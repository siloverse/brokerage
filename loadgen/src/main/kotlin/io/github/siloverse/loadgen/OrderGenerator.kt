package io.github.siloverse.loadgen

import io.github.siloverse.brokerage.orders.application.venue.NewOrder
import io.github.siloverse.brokerage.orders.domain.ClientOrderId
import io.github.siloverse.brokerage.orders.domain.Instrument
import io.github.siloverse.brokerage.orders.domain.Price
import io.github.siloverse.brokerage.orders.domain.Quantity
import io.github.siloverse.brokerage.orders.domain.Side
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Makes the orders of a [Profile]. A share of them is priced away from the mid so it
 * rests; those ids are queued for the cancel stream. The rest cross and fill. Deterministic
 * for a seed, so two runs of the same profile send the same orders.
 */
class OrderGenerator(private val profile: Profile, seed: Long = 1, private val runId: String = "r") {

    private val random = Random(seed)
    private val lock = Any()
    private val counter = AtomicLong()
    private val resting = ConcurrentLinkedQueue<ClientOrderId>()

    fun next(): NewOrder {
        val n = counter.incrementAndGet()
        val (instrument, rests, buy) = synchronized(lock) {
            Triple(pickInstrument(), random.nextDouble() < profile.restingShare, random.nextBoolean())
        }
        val margin = profile.referencePrice * profile.priceMarginPercent / 100
        // A buy above the mid or a sell below it crosses; the opposite rests.
        val price = when {
            buy && !rests -> profile.referencePrice + margin
            buy -> profile.referencePrice - margin
            !rests -> profile.referencePrice - margin
            else -> profile.referencePrice + margin
        }
        val id = ClientOrderId("$runId-$n")
        if (rests) resting += id
        return NewOrder(id, Instrument(instrument), if (buy) Side.BUY else Side.SELL, Price(price), Quantity(profile.quantity))
    }

    /** The oldest resting order not yet cancelled, or null when there is none to cancel. */
    fun nextToCancel(): ClientOrderId? = resting.poll()

    val restingCount: Int get() = resting.size

    private fun pickInstrument(): String {
        val hot = profile.hotShare
        val all = profile.instruments
        return if (hot != null && random.nextDouble() < hot) all.first()
        else all[random.nextInt(all.size)]
    }
}
