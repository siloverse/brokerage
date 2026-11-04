package io.github.siloverse.brokerage.orders.application

/**
 * The venue did not answer in time, or the session is down. This is the "we do not know"
 * outcome of the lifecycle contract: the caller sets `submission_unknown` or
 * `cancel_unknown`, keeps every hold, and lets the sweeper (2.6) find out.
 */
class VenueUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
