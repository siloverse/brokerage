package io.github.siloverse.brokerage.orders

/** Minor units of the instrument's currency (cents). Never a floating-point type. */
@JvmInline value class Price(val minorUnits: Long)

/** Whole shares in v1. Fractional shares, when they come, are a Long in a fixed small unit. */
@JvmInline value class Quantity(val shares: Long)

enum class Side { BUY, SELL }

/** v1 supports DAY only; the venue expires the remainder at session close. */
enum class TimeInForce { DAY }
