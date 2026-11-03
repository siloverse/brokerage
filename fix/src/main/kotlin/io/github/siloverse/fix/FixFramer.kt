package io.github.siloverse.fix

/**
 * Cuts a byte stream into message frames. TCP delivers bytes in arbitrary chunks; this
 * class finds message boundaries so the decoder only ever sees whole messages.
 *
 * A frame is found by: locating `8=`, reading the `9=N` field that must follow, then
 * taking N body bytes and the seven-byte trailer `10=xxx` plus SOH. Bytes before a `8=`
 * are garbage and dropped. A frame whose `9=` field is unreadable is dropped up to the
 * next `8=`, because without the length there is no way to find its end.
 *
 * Not thread-safe: one framer per connection, driven by that connection's reader.
 */
class FixFramer {

    private var buffer = ByteArray(0)

    /** Appends [chunk] and returns every complete frame now available, in order. */
    fun feed(chunk: ByteArray): List<ByteArray> {
        buffer += chunk
        val frames = mutableListOf<ByteArray>()
        while (true) {
            val frame = nextFrame() ?: break
            frames += frame
        }
        return frames
    }

    /** Bytes held back because they do not yet form a complete frame. */
    val pending: Int get() = buffer.size

    private fun nextFrame(): ByteArray? {
        val start = indexOf(BEGIN_MARKER, 0)
        if (start < 0) {
            // No message start anywhere: keep at most one trailing byte in case it is a lone '8'.
            buffer = if (buffer.isNotEmpty() && buffer.last() == '8'.code.toByte()) byteArrayOf('8'.code.toByte()) else ByteArray(0)
            return null
        }
        if (start > 0) buffer = buffer.copyOfRange(start, buffer.size)

        val firstSoh = buffer.indexOf(SOH.code.toByte())
        if (firstSoh < 0) return null
        val lengthFieldStart = firstSoh + 1
        if (buffer.size < lengthFieldStart + LENGTH_MARKER.size) return null
        if (!buffer.startsWith(LENGTH_MARKER, lengthFieldStart)) return dropToNextStart()

        val secondSoh = indexOf(byteArrayOf(SOH.code.toByte()), lengthFieldStart)
        if (secondSoh < 0) return null
        val bodyLength = String(buffer, lengthFieldStart + LENGTH_MARKER.size, secondSoh - lengthFieldStart - LENGTH_MARKER.size, Charsets.ISO_8859_1)
            .toIntOrNull() ?: return dropToNextStart()

        val frameEnd = secondSoh + 1 + bodyLength + TRAILER_LENGTH
        if (buffer.size < frameEnd) return null

        val frame = buffer.copyOfRange(0, frameEnd)
        buffer = buffer.copyOfRange(frameEnd, buffer.size)
        return frame
    }

    private fun dropToNextStart(): ByteArray? {
        val next = indexOf(BEGIN_MARKER, 1)
        buffer = if (next < 0) ByteArray(0) else buffer.copyOfRange(next, buffer.size)
        return if (buffer.isEmpty()) null else nextFrame()
    }

    private fun indexOf(pattern: ByteArray, from: Int): Int {
        outer@ for (i in from..buffer.size - pattern.size) {
            for (j in pattern.indices) if (buffer[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }

    private fun ByteArray.startsWith(pattern: ByteArray, at: Int): Boolean =
        pattern.indices.all { this[at + it] == pattern[it] }

    private companion object {
        val BEGIN_MARKER = "8=FIX".toByteArray(Charsets.ISO_8859_1)
        val LENGTH_MARKER = "9=".toByteArray(Charsets.ISO_8859_1)
        /** `10=xxx` plus SOH. */
        const val TRAILER_LENGTH = 7
    }
}
