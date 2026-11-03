package io.github.siloverse.fix.store

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.WRITE

/** Runs the shared contract against the file store, then adds what only a file can prove. */
class FileMessageStoreTest : MessageStoreContract() {

    @TempDir
    lateinit var root: Path

    private val open = mutableListOf<FileMessageStore>()
    private var count = 0

    override fun store(): MessageStore = FileMessageStore(root.resolve("session-${count++}")).also { open += it }

    @AfterEach
    fun closeAll() = open.forEach { it.close() }

    private fun bytesFor(seq: Long) = "msg-$seq".toByteArray()

    @Test
    fun `restart recovers both counters and the outbound log`() {
        val dir = root.resolve("restart")
        FileMessageStore(dir).use { first ->
            repeat(3) { first.append(::bytesFor) }
            first.setNextExpectedInbound(4712)
        }

        FileMessageStore(dir).use { second ->
            assertEquals(4L, second.nextOutbound())
            assertEquals(4712L, second.nextExpectedInbound())
            assertEquals(listOf(1L, 2L, 3L), second.outbound(1).map { it.seq })
            assertArrayEquals(bytesFor(2), second.outbound(2, 2).single().bytes)
            assertEquals(4L, second.append(::bytesFor).seq)
        }
    }

    @Test
    fun `a torn record at the tail of the message log is cut off on restart`() {
        val dir = root.resolve("torn")
        FileMessageStore(dir).use { store -> repeat(2) { store.append(::bytesFor) } }

        // Simulate a crash mid-write: a header claiming 100 bytes, followed by only 3.
        FileChannel.open(dir.resolve("messages.log"), WRITE, APPEND).use { channel ->
            channel.write(java.nio.ByteBuffer.allocate(12 + 3).putLong(3).putInt(100).put("abc".toByteArray()).flip())
        }

        FileMessageStore(dir).use { store ->
            assertEquals(listOf(1L, 2L), store.outbound(1).map { it.seq })
            assertEquals(3L, store.nextOutbound())
            assertEquals(3L, store.append(::bytesFor).seq)
            assertEquals(listOf(1L, 2L, 3L), store.outbound(1).map { it.seq })
        }
    }

    @Test
    fun `a torn inbound record is ignored and the message log sets the outbound number`() {
        val dir = root.resolve("torn-counters")
        FileMessageStore(dir).use { store ->
            repeat(5) { store.append(::bytesFor) }
            store.setNextExpectedInbound(77)
        }
        FileChannel.open(dir.resolve("inbound.log"), WRITE, APPEND).use { channel ->
            channel.write(java.nio.ByteBuffer.allocate(5).put("12345".toByteArray()).flip())
        }

        FileMessageStore(dir).use { store ->
            assertEquals(77L, store.nextExpectedInbound())
            assertEquals(6L, store.nextOutbound())
        }
    }

    @Test
    fun `a lost inbound file cannot reuse an outbound number`() {
        val dir = root.resolve("lost-counters")
        FileMessageStore(dir).use { store -> repeat(4) { store.append(::bytesFor) } }
        // Inbound file lost entirely; the message log alone owns the outbound number.
        Files.delete(dir.resolve("inbound.log"))

        FileMessageStore(dir).use { store ->
            assertEquals(5L, store.nextOutbound())
            assertEquals(1L, store.nextExpectedInbound())   // the harmless side: we will over-ask for a resend
        }
    }

    @Test
    fun `reset empties both files on disk`() {
        val dir = root.resolve("reset")
        FileMessageStore(dir).use { store ->
            repeat(3) { store.append(::bytesFor) }
            store.setNextExpectedInbound(9)
            store.reset()
        }

        assertEquals(0L, Files.size(dir.resolve("messages.log")))
        assertEquals(8L, Files.size(dir.resolve("inbound.log")))
        FileMessageStore(dir).use { store ->
            assertEquals(1L, store.nextOutbound())
            assertEquals(1L, store.nextExpectedInbound())
            assertTrue(store.outbound(1).isEmpty())
        }
    }
}
