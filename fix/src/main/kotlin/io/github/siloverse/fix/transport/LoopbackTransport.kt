package io.github.siloverse.fix.transport

import java.io.IOException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

/**
 * Two [Transport]s joined in memory: what one writes, the other reads. For tests, and
 * for the chaos drills that need to cut a connection at an exact byte.
 */
class LoopbackTransport private constructor(
    private val inbox: BlockingQueue<ByteArray>,
    private val outbox: BlockingQueue<ByteArray>,
) : Transport {

    @Volatile private var closed = false

    override fun read(): ByteArray {
        if (closed) throw IOException("transport closed")
        val chunk = inbox.take()
        return if (chunk === EOF) { inbox.put(EOF); ByteArray(0) } else chunk
    }

    override fun write(bytes: ByteArray) {
        if (closed) throw IOException("transport closed")
        outbox.put(bytes.copyOf())
    }

    override fun close() {
        if (closed) return
        closed = true
        outbox.put(EOF)   // the peer's next read returns empty
        inbox.put(EOF)    // our own blocked read, if any, wakes up
    }

    companion object {
        private val EOF = ByteArray(0)

        /** A connected pair: `first.write` is read by `second.read` and the other way round. */
        fun pair(): Pair<LoopbackTransport, LoopbackTransport> {
            val aToB = LinkedBlockingQueue<ByteArray>()
            val bToA = LinkedBlockingQueue<ByteArray>()
            return LoopbackTransport(inbox = bToA, outbox = aToB) to LoopbackTransport(inbox = aToB, outbox = bToA)
        }
    }
}
