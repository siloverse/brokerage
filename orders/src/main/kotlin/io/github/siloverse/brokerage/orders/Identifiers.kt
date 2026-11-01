package io.github.siloverse.brokerage.orders

/** Broker-assigned before persistence; the idempotency key for [Venue.submit]. FIX ClOrdID (11). */
@JvmInline value class ClientOrderId(val value: String)

/** Venue-assigned at receipt; an identifier, not an idempotency key. FIX OrderID (37). */
@JvmInline value class VenueOrderId(val value: String)

/** Venue-assigned per fill, immutable; the key the broker deduplicates reports on. FIX ExecID (17) */
@JvmInline value class ExecutionId(val value: String)

/** Per-session, per-direction counter the broker uses to detect gaps. FIX MsgSeqNum (34). */
@JvmInline value class SequenceNumber(val value: Long)

@JvmInline value class Instrument(val symbol: String)
