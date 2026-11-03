package io.github.siloverse.fix.transport

import java.io.Closeable

/**
 * One open connection as the session sees it: bytes in, bytes out, nothing else. TCP
 * sockets implement it for real; a loopback pair implements it for tests. The session
 * never learns which.
 */
interface Transport : Closeable {

    /**
     * Blocks until some bytes arrive and returns them, or returns an empty array when the
     * peer closed the connection. Throws on a broken connection.
     */
    fun read(): ByteArray

    /** Writes all of [bytes]. Throws on a broken connection. */
    fun write(bytes: ByteArray)

    /** Closes the connection; a blocked [read] on the other side returns empty. */
    override fun close()
}
