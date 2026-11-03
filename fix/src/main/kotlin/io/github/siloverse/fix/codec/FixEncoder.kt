package io.github.siloverse.fix.codec

import io.github.siloverse.fix.message.FixField
import io.github.siloverse.fix.message.FixMessage
import io.github.siloverse.fix.message.Tag

/**
 * Turns a [FixMessage] into wire bytes. Computes BodyLength (9) and CheckSum (10) itself;
 * any 8, 9 or 10 present on the input is dropped and recomputed, so a decoded message can
 * be re-encoded for a resend.
 *
 * BodyLength counts the bytes after the SOH that ends the `9=` field, up to and including
 * the SOH before `10=`. CheckSum is the sum of every byte before `10=`, modulo 256, as
 * three decimal digits.
 */
object FixEncoder {

    const val BEGIN_STRING = "FIX.4.4"

    fun encode(message: FixMessage): ByteArray {
        val body = StringBuilder()
        for (field in message.fields) {
            when (field.tag) {
                Tag.BEGIN_STRING, Tag.BODY_LENGTH, Tag.CHECK_SUM -> continue
                else -> body.append(field.tag).append('=').append(field.value).append(SOH)
            }
        }
        require(body.isNotEmpty()) { "a FIX message needs at least a MsgType" }

        val bodyBytes = body.toString().toByteArray(Charsets.ISO_8859_1)
        val head = "${Tag.BEGIN_STRING}=$BEGIN_STRING$SOH${Tag.BODY_LENGTH}=${bodyBytes.size}$SOH"
            .toByteArray(Charsets.ISO_8859_1)

        val withoutTrailer = head + bodyBytes
        val checksum = withoutTrailer.sumOf { it.toInt() and 0xFF } % 256
        val trailer = "${Tag.CHECK_SUM}=${checksum.toString().padStart(3, '0')}$SOH"
            .toByteArray(Charsets.ISO_8859_1)

        return withoutTrailer + trailer
    }
}
