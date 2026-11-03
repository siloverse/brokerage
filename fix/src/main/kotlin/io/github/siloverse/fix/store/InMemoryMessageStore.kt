package io.github.siloverse.fix.store

import java.util.TreeMap

/** Keeps everything on the heap. Fine for tests and for a venue that may forget on restart. */
class InMemoryMessageStore : MessageStore {

    private val lock = Any()
    private var nextOut = 1L
    private var nextIn = 1L
    private val log = TreeMap<Long, StoredMessage>()

    override fun nextOutbound(): Long = synchronized(lock) { nextOut }

    override fun nextExpectedInbound(): Long = synchronized(lock) { nextIn }

    override fun append(encode: (seq: Long) -> ByteArray): StoredMessage = synchronized(lock) {
        val seq = nextOut
        val bytes = encode(seq)               // may throw: then nothing below happens
        val stored = StoredMessage(seq, bytes)
        log[seq] = stored
        nextOut = seq + 1
        stored
    }

    override fun outbound(from: Long, to: Long): List<StoredMessage> = synchronized(lock) {
        val upper = if (to == 0L) Long.MAX_VALUE else to
        log.subMap(from, true, upper, true).values.toList()
    }

    override fun setNextExpectedInbound(seq: Long) = synchronized(lock) {
        require(seq >= 1) { "sequence numbers start at 1, got $seq" }
        nextIn = seq
    }

    override fun reset() = synchronized(lock) {
        nextOut = 1
        nextIn = 1
        log.clear()
    }
}
