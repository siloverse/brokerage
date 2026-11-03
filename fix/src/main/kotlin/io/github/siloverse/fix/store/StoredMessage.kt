package io.github.siloverse.fix.store

/** One outbound message as it went on the wire, with the sequence number it carried. */
class StoredMessage(val seq: Long, val bytes: ByteArray)
