package io.github.siloverse.venue.engine

import kotlin.random.Random

/** One random-walk mid price per instrument, in minor units. Deterministic for a seed. */
class PriceFeed(private val startPrice: Long, private val step: Long, seed: Long) {

    private val random = Random(seed)
    private val mids = HashMap<String, Long>()

    fun mid(symbol: String): Long = mids.getOrPut(symbol) { startPrice }

    /** Moves every known instrument by a random amount in `[-step, step]`, never below 1. */
    fun tick() {
        if (step == 0L) return
        for (symbol in mids.keys.toList()) {
            val move = random.nextLong(-step, step + 1)
            mids[symbol] = maxOf(1L, mids.getValue(symbol) + move)
        }
    }

    /** Pin an instrument to a price: the chaos knob for "make this order fill now". */
    fun set(symbol: String, mid: Long) { mids[symbol] = mid }
}
