package io.github.siloverse.fix.store

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
