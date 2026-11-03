package io.github.siloverse.fix.codec

import io.github.siloverse.fix.message.FixField
import io.github.siloverse.fix.message.FixMessage
import io.github.siloverse.fix.message.Tag

/**
 * Turns one complete frame of wire bytes into a [FixMessage], or throws
 * [FixGarbledException]. Checks, in order: the frame starts with `8=`, the second field
 * is `9=`, the last field is `10=`, the body length matches, the checksum matches.
 * The decoded message keeps fields 8, 9 and 10 so a stored message round-trips.
 */
object FixDecoder {

    fun decode(frame: ByteArray): FixMessage {
        val text = String(frame, Charsets.ISO_8859_1)
        if (text.isEmpty() || text.last() != SOH) throw FixGarbledException("frame does not end with SOH")

        val fields = text.dropLast(1).split(SOH).map { raw ->
            val eq = raw.indexOf('=')
            if (eq <= 0) throw FixGarbledException("field without tag: '$raw'")
            val tag = raw.substring(0, eq).toIntOrNull()
                ?: throw FixGarbledException("non-numeric tag: '$raw'")
            FixField(tag, raw.substring(eq + 1))
        }

        if (fields.size < 3) throw FixGarbledException("too few fields")
        if (fields[0].tag != Tag.BEGIN_STRING) throw FixGarbledException("first field is not BeginString")
        if (fields[1].tag != Tag.BODY_LENGTH) throw FixGarbledException("second field is not BodyLength")
        if (fields.last().tag != Tag.CHECK_SUM) throw FixGarbledException("last field is not CheckSum")

        val declaredLength = fields[1].value.toIntOrNull()
            ?: throw FixGarbledException("BodyLength is not a number")
        val bodyStart = text.indexOf(SOH, text.indexOf(SOH) + 1) + 1   // after the SOH ending 9=
        val trailerStart = text.lastIndexOf("${Tag.CHECK_SUM}=")
        val actualLength = frame.size - bodyStart - (frame.size - trailerStart)
        if (actualLength != declaredLength) {
            throw FixGarbledException("BodyLength $declaredLength but body has $actualLength bytes")
        }

        val declaredChecksum = fields.last().value.toIntOrNull()
            ?: throw FixGarbledException("CheckSum is not a number")
        val actualChecksum = frame.take(trailerStart).sumOf { it.toInt() and 0xFF } % 256
        if (actualChecksum != declaredChecksum) {
            throw FixGarbledException("CheckSum $declaredChecksum but computed $actualChecksum")
        }

        return FixMessage(fields)
    }
}
