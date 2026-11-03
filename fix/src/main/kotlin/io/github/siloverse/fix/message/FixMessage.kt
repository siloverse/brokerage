package io.github.siloverse.fix.message

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * An ordered list of fields. Order matters: the header must come first and repeating
 * groups depend on it. Fields 8, 9 and 10 are the encoder's business; a message built by
 * callers never carries them, a decoded message always does.
 */
class FixMessage(val fields: List<FixField>) {

    operator fun get(tag: Int): String? = fields.firstOrNull { it.tag == tag }?.value

    fun require(tag: Int): String = get(tag) ?: throw MissingTagException(tag)

    fun getInt(tag: Int): Int? = get(tag)?.toInt()

    fun getLong(tag: Int): Long? = get(tag)?.toLong()

    fun getInstant(tag: Int): Instant? = get(tag)?.let { parseUtcTimestamp(it) }

    val msgType: String get() = require(Tag.MSG_TYPE)

    val seqNum: Long get() = require(Tag.MSG_SEQ_NUM).toLong()

    /** A copy with [field] appended, or replaced if the tag is already present. */
    fun with(field: FixField): FixMessage {
        val without = fields.filterNot { it.tag == field.tag }
        return FixMessage(without + field)
    }

    /** Human-readable form with `|` for SOH. Never send this; it is for logs and asserts. */
    override fun toString(): String = fields.joinToString("") { "${it.tag}=${it.value}|" }

    override fun equals(other: Any?): Boolean = other is FixMessage && other.fields == fields

    override fun hashCode(): Int = fields.hashCode()

    companion object {
        /**
         * Builds a message ready for [FixEncoder]: the standard header in spec order
         * (35, 49, 56, 34, 52) followed by [body] in the order given.
         */
        fun create(
            msgType: String,
            senderCompId: String,
            targetCompId: String,
            seqNum: Long,
            sendingTime: Instant,
            body: List<FixField> = emptyList(),
        ): FixMessage = FixMessage(
            listOf(
                FixField(Tag.MSG_TYPE, msgType),
                FixField(Tag.SENDER_COMP_ID, senderCompId),
                FixField(Tag.TARGET_COMP_ID, targetCompId),
                FixField(Tag.MSG_SEQ_NUM, seqNum.toString()),
                FixField(Tag.SENDING_TIME, formatUtcTimestamp(sendingTime)),
            ) + body,
        )

        private val UTC_TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS").withZone(ZoneOffset.UTC)

        /** FIX UTCTimestamp with milliseconds, always in UTC. */
        fun formatUtcTimestamp(instant: Instant): String = UTC_TIMESTAMP.format(instant)

        fun parseUtcTimestamp(text: String): Instant = Instant.from(UTC_TIMESTAMP.parse(text))
    }
}
