package io.github.siloverse.loadgen

/** What the generator sends. [FILL] is not sent: it is the time from submit to the first fill report. */
enum class RequestType { ORDER, CANCEL, QUOTE, PORTFOLIO, FILL }
