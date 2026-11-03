package io.github.siloverse.fix.codec

/** The bytes were not a valid FIX message. The session layer ignores garbled input; it never replies to it. */
class FixGarbledException(message: String) : RuntimeException(message)
