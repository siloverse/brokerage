package io.github.siloverse.fix

/** One outbound message as it went on the wire, with the sequence number it carried. */
class StoredMessage(val seq: Long, val bytes: ByteArray)

/**
 * What a session must remember across a crash: the two counters and every outbound
 * message, so the peer can ask for a resend.
 *
 * ## Contract
 * 1. [append] is the only way to send: it hands out the next outbound number, persists
 *    the message **and** the counter advance as one step, and only then returns. The
 *    caller writes to the socket after that, never before. If the encoder throws, nothing
 *    is stored and the counter does not move.
 * 2. Counters start at 1 and only [reset] takes them back there.
 * 3. [outbound] returns what was stored, in ascending order, and nothing for sequence
 *    numbers that were never appended or were dropped by [reset]. The session turns such
 *    holes into gap fills; the store does not invent messages.
 * 4. A store is used by one session at a time. Implementations are safe to call from the
 *    reader and the writer thread of that session.
 */
interface MessageStore {

    /** The sequence number the next [append] will assign. */
    fun nextOutbound(): Long

    /** The sequence number this side expects on the next inbound message. */
    fun nextExpectedInbound(): Long

    /**
     * Assigns the next outbound number, calls [encode] with it, persists the result and
     * advances the counter atomically. Returns what was stored.
     */
    fun append(encode: (seq: Long) -> ByteArray): StoredMessage

    /**
     * Stored outbound messages with `from <= seq <= to`, ascending. `to = 0` means
     * "to the end", as in ResendRequest's EndSeqNo (16).
     */
    fun outbound(from: Long, to: Long = 0): List<StoredMessage>

    /** Advances (or, after a SequenceReset, moves) the expected inbound number. */
    fun setNextExpectedInbound(seq: Long)

    /** Both counters back to 1 and the outbound log emptied: the effect of Logon with 141=Y. */
    fun reset()
}

/** Keeps everything on the heap. Fine for tests and for a venue that may forget on restart. */
class InMemoryMessageStore : MessageStore {

    private val lock = Any()
    private var nextOut = 1L
    private var nextIn = 1L
    private val log = java.util.TreeMap<Long, StoredMessage>()

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
