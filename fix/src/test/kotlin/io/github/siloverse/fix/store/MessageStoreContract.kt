package io.github.siloverse.fix.store

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The [MessageStore] contract. Every implementation (in memory now, file-backed next)
 * subclasses this and passes all of it. Tests are numbered after the contract in the KDoc.
 */
abstract class MessageStoreContract {

    protected abstract fun store(): MessageStore

    private fun bytesFor(seq: Long) = "msg-$seq".toByteArray()

    @Test
    fun `2 - counters start at 1`() {
        val store = store()

        assertEquals(1L, store.nextOutbound())
        assertEquals(1L, store.nextExpectedInbound())
    }

    @Test
    fun `1 - append hands out consecutive numbers and the encoder sees them`() {
        val store = store()
        val seen = mutableListOf<Long>()

        val first = store.append { seq -> seen += seq; bytesFor(seq) }
        val second = store.append { seq -> seen += seq; bytesFor(seq) }

        assertEquals(listOf(1L, 2L), seen)
        assertEquals(1L, first.seq)
        assertEquals(2L, second.seq)
        assertArrayEquals(bytesFor(2), second.bytes)
        assertEquals(3L, store.nextOutbound())
    }

    @Test
    fun `1 - an encoder failure stores nothing and leaves the counter alone`() {
        val store = store()
        store.append(::bytesFor)

        assertThrows(IllegalStateException::class.java) {
            store.append { throw IllegalStateException("encoder broke") }
        }

        assertEquals(2L, store.nextOutbound())
        assertEquals(1, store.outbound(1).size)
        assertEquals(2L, store.append(::bytesFor).seq)
    }

    @Test
    fun `3 - outbound returns a closed range in ascending order`() {
        val store = store()
        repeat(5) { store.append(::bytesFor) }

        val range = store.outbound(2, 4)

        assertEquals(listOf(2L, 3L, 4L), range.map { it.seq })
        assertArrayEquals(bytesFor(3), range[1].bytes)
    }

    @Test
    fun `3 - outbound with to equal to zero means to the end`() {
        val store = store()
        repeat(5) { store.append(::bytesFor) }

        assertEquals(listOf(3L, 4L, 5L), store.outbound(3).map { it.seq })
        assertEquals(listOf(3L, 4L, 5L), store.outbound(3, 0).map { it.seq })
    }

    @Test
    fun `3 - outbound beyond what was stored is empty, never invented`() {
        val store = store()
        repeat(2) { store.append(::bytesFor) }

        assertTrue(store.outbound(3).isEmpty())
        assertTrue(store.outbound(5, 9).isEmpty())
    }

    @Test
    fun `expected inbound moves only when told`() {
        val store = store()

        store.setNextExpectedInbound(4712)
        store.append(::bytesFor)

        assertEquals(4712L, store.nextExpectedInbound())
    }

    @Test
    fun `expected inbound below 1 is refused`() {
        assertThrows(IllegalArgumentException::class.java) { store().setNextExpectedInbound(0) }
    }

    @Test
    fun `2 - reset takes both counters to 1 and empties the log`() {
        val store = store()
        repeat(3) { store.append(::bytesFor) }
        store.setNextExpectedInbound(300)

        store.reset()

        assertEquals(1L, store.nextOutbound())
        assertEquals(1L, store.nextExpectedInbound())
        assertTrue(store.outbound(1).isEmpty())
        assertEquals(1L, store.append(::bytesFor).seq)
    }

    @Test
    fun `4 - concurrent appends never share or skip a number`() {
        val store = store()
        val threads = 8
        val perThread = 250
        val workers = (1..threads).map {
            Thread { repeat(perThread) { store.append(::bytesFor) } }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }

        val all = store.outbound(1)
        assertEquals(threads * perThread, all.size)
        assertEquals((1L..(threads * perThread)).toList(), all.map { it.seq })
        assertEquals(threads * perThread + 1L, store.nextOutbound())
    }
}
