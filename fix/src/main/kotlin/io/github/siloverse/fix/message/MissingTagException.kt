package io.github.siloverse.fix.message

/** A well-formed message lacked a tag the caller needs. A session answers this with Reject (35=3), not by ignoring it. */
class MissingTagException(val tag: Int) : RuntimeException("missing tag $tag")
