package io.github.siloverse.fix.transport

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LoopbackTransportTest {

    @Test
    fun `what one side writes the other side reads, in order`() {
        val (a, b) = LoopbackTransport.pair()

        a.write("one".toByteArray())
        a.write("two".toByteArray())
        b.write("back".toByteArray())

        assertArrayEquals("one".toByteArray(), b.read())
        assertArrayEquals("two".toByteArray(), b.read())
        assertArrayEquals("back".toByteArray(), a.read())
    }

    @Test
    fun `closing one side makes the peer read empty and the closer throw`() {
        val (a, b) = LoopbackTransport.pair()

        a.close()

        assertEquals(0, b.read().size)
        assertEquals(0, b.read().size)   // stays closed
        assertThrows(IOException::class.java) { a.read() }
        assertThrows(IOException::class.java) { a.write(ByteArray(1)) }
    }

    @Test
    fun `a blocked read wakes up when the peer closes`() {
        val (a, b) = LoopbackTransport.pair()
        val executor = Executors.newSingleThreadExecutor()
        val pending = executor.submit<Int> { b.read().size }

        Thread.sleep(50)
        a.close()

        assertEquals(0, pending.get(2, TimeUnit.SECONDS))
        executor.shutdownNow()
    }

    @Test
    fun `the writer's buffer can be reused after write`() {
        val (a, b) = LoopbackTransport.pair()
        val buffer = "first".toByteArray()

        a.write(buffer)
        buffer.fill(0)

        assertArrayEquals("first".toByteArray(), b.read())
    }
}
