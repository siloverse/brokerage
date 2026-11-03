package io.github.siloverse.fix.store

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.TreeMap

/**
 * A [MessageStore] that survives a process restart. Two append-only files in [directory]:
 *
 * - `messages.log`: one record per sent message, `seq (8) | length (4) | bytes`.
 * - `inbound.log`: one record per change of the expected inbound number, `seq (8)`.
 *
 * Each file owns exactly one fact: `messages.log` is the only source of the outbound
 * number (last record's seq plus one), `inbound.log` the only source of the inbound one.
 * No fact lives in two files, so the two writes never need a transaction around them.
 * Recovery reads the last complete record of each file; a torn tail (crash mid-write) is
 * cut off.
 *
 * Durability: `messages.log` is forced to disk on every [append], because a reused
 * outbound number ends the session. `inbound.log` is appended on every change but not
 * forced: losing the tail only makes this side ask for a resend it did not need, which
 * the peer answers with PossDup messages and the application deduplicates.
 *
 * `inbound.log` grows by 8 bytes per inbound message; [reset] truncates both files.
 */
class FileMessageStore(directory: Path) : MessageStore, Closeable {

    private val lock = Any()
    private val messages: FileChannel
    private val inbound: FileChannel
    private val index = TreeMap<Long, Pair<Long, Int>>()   // seq -> (offset of payload, length)
    private var nextOut = 1L
    private var nextIn = 1L

    init {
        Files.createDirectories(directory)
        messages = FileChannel.open(directory.resolve("messages.log"), CREATE, READ, WRITE)
        inbound = FileChannel.open(directory.resolve("inbound.log"), CREATE, READ, WRITE)
        recover()
    }

    override fun nextOutbound(): Long = synchronized(lock) { nextOut }

    override fun nextExpectedInbound(): Long = synchronized(lock) { nextIn }

    override fun append(encode: (seq: Long) -> ByteArray): StoredMessage = synchronized(lock) {
        val seq = nextOut
        val bytes = encode(seq)
        val record = ByteBuffer.allocate(MESSAGE_HEADER + bytes.size)
            .putLong(seq).putInt(bytes.size).put(bytes).flip()
        val recordStart = messages.size()
        writeFully(messages, record)
        messages.force(false)
        index[seq] = (recordStart + MESSAGE_HEADER) to bytes.size
        nextOut = seq + 1
        StoredMessage(seq, bytes)
    }

    override fun outbound(from: Long, to: Long): List<StoredMessage> = synchronized(lock) {
        val upper = if (to == 0L) Long.MAX_VALUE else to
        index.subMap(from, true, upper, true).map { (seq, location) ->
            val (offset, length) = location
            val buffer = ByteBuffer.allocate(length)
            var position = offset
            while (buffer.hasRemaining()) {
                val read = messages.read(buffer, position)
                check(read > 0) { "messages.log is shorter than its index for seq $seq" }
                position += read
            }
            StoredMessage(seq, buffer.array())
        }
    }

    override fun setNextExpectedInbound(seq: Long) = synchronized(lock) {
        require(seq >= 1) { "sequence numbers start at 1, got $seq" }
        nextIn = seq
        appendInbound()
    }

    override fun reset() = synchronized(lock) {
        messages.truncate(0)
        messages.force(false)
        inbound.truncate(0)
        index.clear()
        nextOut = 1
        nextIn = 1
        appendInbound()
    }

    override fun close() = synchronized(lock) {
        messages.close()
        inbound.close()
    }

    private fun appendInbound() {
        writeFully(inbound, ByteBuffer.allocate(INBOUND_RECORD).putLong(nextIn).flip())
    }

    private fun recover() {
        // inbound.log: last complete record wins; a torn tail is cut off.
        val inboundSize = inbound.size()
        val complete = inboundSize - inboundSize % INBOUND_RECORD
        if (complete > 0) {
            val last = ByteBuffer.allocate(INBOUND_RECORD)
            inbound.read(last, complete - INBOUND_RECORD)
            last.flip()
            nextIn = last.getLong()
        }
        if (complete < inboundSize) inbound.truncate(complete)

        // messages.log: rebuild the index; stop at the first torn record.
        val size = messages.size()
        var position = 0L
        val header = ByteBuffer.allocate(MESSAGE_HEADER)
        while (position + MESSAGE_HEADER <= size) {
            header.clear()
            messages.read(header, position)
            header.flip()
            val seq = header.getLong()
            val length = header.getInt()
            if (length < 0 || position + MESSAGE_HEADER + length > size) break
            index[seq] = (position + MESSAGE_HEADER) to length
            position += MESSAGE_HEADER + length
        }
        if (position < size) {
            messages.truncate(position)
            messages.force(false)
        }
        nextOut = if (index.isEmpty()) 1L else index.lastKey() + 1
    }

    /** Positional write at the end of the file: READ and APPEND cannot be combined on a FileChannel. */
    private fun writeFully(channel: FileChannel, buffer: ByteBuffer) {
        var position = channel.size()
        while (buffer.hasRemaining()) position += channel.write(buffer, position)
    }

    private companion object {
        const val MESSAGE_HEADER = Long.SIZE_BYTES + Int.SIZE_BYTES
        const val INBOUND_RECORD = Long.SIZE_BYTES
    }
}
