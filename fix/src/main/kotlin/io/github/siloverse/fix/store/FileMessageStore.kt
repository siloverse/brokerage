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
 * - `counters.log`: one record per counter change, `nextExpectedInbound (8) | nextOutbound (8)`.
 *
 * Recovery reads the last complete record of each file. A torn record at the tail (crash
 * mid-write) is cut off. The outbound number is the larger of the counters record and
 * the last message seq plus one, so a crash between the two writes is harmless either way.
 *
 * Durability: `messages.log` is forced to disk on every [append], because a reused
 * outbound number ends the session. `counters.log` is appended on every change but not
 * forced: losing the tail only makes this side ask for a resend it did not need, which
 * the peer answers with PossDup messages and the application deduplicates.
 *
 * `counters.log` grows by 16 bytes per inbound message; [reset] truncates both files.
 */
class FileMessageStore(directory: Path) : MessageStore, Closeable {

    private val lock = Any()
    private val messages: FileChannel
    private val counters: FileChannel
    private val index = TreeMap<Long, Pair<Long, Int>>()   // seq -> (offset of payload, length)
    private var nextOut = 1L
    private var nextIn = 1L

    init {
        Files.createDirectories(directory)
        messages = FileChannel.open(directory.resolve("messages.log"), CREATE, READ, WRITE)
        counters = FileChannel.open(directory.resolve("counters.log"), CREATE, READ, WRITE)
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
        appendCounters()
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
        appendCounters()
    }

    override fun reset() = synchronized(lock) {
        messages.truncate(0)
        messages.force(false)
        counters.truncate(0)
        index.clear()
        nextOut = 1
        nextIn = 1
        appendCounters()
    }

    override fun close() = synchronized(lock) {
        messages.close()
        counters.close()
    }

    private fun appendCounters() {
        writeFully(counters, ByteBuffer.allocate(COUNTERS_RECORD).putLong(nextIn).putLong(nextOut).flip())
    }

    private fun recover() {
        // counters.log: last complete record wins; a torn tail is cut off.
        val countersSize = counters.size()
        val complete = countersSize - countersSize % COUNTERS_RECORD
        if (complete > 0) {
            val last = ByteBuffer.allocate(COUNTERS_RECORD)
            counters.read(last, complete - COUNTERS_RECORD)
            last.flip()
            nextIn = last.getLong()
            nextOut = last.getLong()
        }
        if (complete < countersSize) counters.truncate(complete)

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
        if (index.isNotEmpty()) nextOut = maxOf(nextOut, index.lastKey() + 1)
    }

    /** Positional write at the end of the file: READ and APPEND cannot be combined on a FileChannel. */
    private fun writeFully(channel: FileChannel, buffer: ByteBuffer) {
        var position = channel.size()
        while (buffer.hasRemaining()) position += channel.write(buffer, position)
    }

    private companion object {
        const val MESSAGE_HEADER = Long.SIZE_BYTES + Int.SIZE_BYTES
        const val COUNTERS_RECORD = Long.SIZE_BYTES * 2
    }
}
