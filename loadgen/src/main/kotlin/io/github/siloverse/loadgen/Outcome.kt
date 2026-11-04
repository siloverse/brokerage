package io.github.siloverse.loadgen

/**
 * How one request ended. Only [OK] contributes to the latency percentiles; the rest are
 * counted on their own so a fast failure can never flatter the success latency
 * (docs/numbers.md section 6).
 */
enum class Outcome {
    OK,
    /** The system answered "no" (a reject). Fast and correct, but not a success. */
    REJECTED,
    /** No answer within the reply timeout. */
    TIMEOUT,
    /** Anything else: exception, connection down. */
    ERROR,
    /** Nothing to send, e.g. a cancel when no order is resting. Never counted as traffic. */
    SKIPPED,
}
