package io.github.siloverse.fix.session

/** Things the driver and the application need to know about, beyond bytes and messages. */
sealed interface SessionEvent {

    /** Both Logons exchanged. */
    data object LoggedOn : SessionEvent

    /** The session asked the driver to drop the transport. Counters are kept unless [resetCounters]. */
    data class Disconnect(val reason: String) : SessionEvent

    /** Inbound bytes that were not a FIX message. Ignored, as the spec says, but counted. */
    data class Garbled(val reason: String) : SessionEvent

    /** A gap was seen and a ResendRequest sent for `[from, to]` (`to = 0` means to the end). */
    data class GapDetected(val from: Long, val to: Long) : SessionEvent

    /** The peer rejected our message [refSeqNum]. The application decides what that means. */
    data class PeerRejected(val refSeqNum: Long, val text: String?) : SessionEvent

    /** The peer sent Logout; [text] is its reason if it gave one. */
    data class PeerLoggedOut(val text: String?) : SessionEvent

    /** The application's listener threw while handling [what]. The session carries on; the application must not. */
    data class ListenerFailed(val what: String, val cause: Throwable) : SessionEvent
}
