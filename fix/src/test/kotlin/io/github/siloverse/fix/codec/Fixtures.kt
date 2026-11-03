package io.github.siloverse.fix.codec

import io.github.siloverse.fix.message.FixField
import io.github.siloverse.fix.message.FixMessage
import java.time.Instant

/** Known-good wire strings, lengths and checksums computed by hand from the spec rules. */
object Fixtures {

    /** Converts the readable `|` form into real wire bytes. */
    fun wire(readable: String): ByteArray =
        readable.replace('|', SOH).toByteArray(Charsets.ISO_8859_1)

    fun readable(bytes: ByteArray): String =
        String(bytes, Charsets.ISO_8859_1).replace(SOH, '|')

    const val HEARTBEAT =
        "8=FIX.4.4|9=54|35=0|49=BROKER|56=VENUE|34=2|52=20261103-10:00:00.000|10=254|"

    const val NEW_ORDER_SINGLE =
        "8=FIX.4.4|9=127|35=D|49=BROKER|56=VENUE|34=3|52=20261103-10:00:01.000|" +
            "11=ord-1|55=ACME|54=1|38=100|40=2|44=10.00|59=0|60=20261103-10:00:01.000|10=081|"

    val heartbeat: FixMessage = FixMessage.create(
        msgType = "0",
        senderCompId = "BROKER",
        targetCompId = "VENUE",
        seqNum = 2,
        sendingTime = Instant.parse("2026-11-03T10:00:00Z"),
    )

    val newOrderSingle: FixMessage = FixMessage.create(
        msgType = "D",
        senderCompId = "BROKER",
        targetCompId = "VENUE",
        seqNum = 3,
        sendingTime = Instant.parse("2026-11-03T10:00:01Z"),
        body = listOf(
            FixField(11, "ord-1"),
            FixField(55, "ACME"),
            FixField(54, "1"),
            FixField(38, "100"),
            FixField(40, "2"),
            FixField(44, "10.00"),
            FixField(59, "0"),
            FixField(60, "20261103-10:00:01.000"),
        ),
    )
}
