package io.github.siloverse.brokerage.orders.infrastructure.fix

import java.time.Duration

/** Where the venue is and who we are to it. */
data class FixVenueConfig(
    val host: String,
    val port: Int,
    val senderCompId: String = "BROKER",
    val targetCompId: String = "VENUE",
    val heartBtInt: Duration = Duration.ofSeconds(30),
    val reconnectBackoff: Duration = Duration.ofSeconds(1),
    /** How long [FixVenue.submit] and [FixVenue.lookup] wait for the venue's answer before giving up. */
    val replyTimeout: Duration = Duration.ofSeconds(5),
    /** How long a call waits for the session to be logged on before failing. */
    val connectTimeout: Duration = Duration.ofSeconds(10),
    /**
     * Send ResetSeqNumFlag (141=Y) on Logon so both sides start at 1. Right for a throwaway
     * session such as the load generator; wrong for the broker, whose store must carry the
     * sequence numbers across restarts so nothing is lost or replayed.
     */
    val resetOnLogon: Boolean = false,
)
