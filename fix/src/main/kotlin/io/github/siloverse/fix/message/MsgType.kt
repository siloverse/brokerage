package io.github.siloverse.fix.message

/** Message type codes (tag 35) of the session layer. */
object MsgType {
    const val HEARTBEAT = "0"
    const val TEST_REQUEST = "1"
    const val RESEND_REQUEST = "2"
    const val REJECT = "3"
    const val SEQUENCE_RESET = "4"
    const val LOGOUT = "5"
    const val LOGON = "A"

    /** Session (admin) messages are never resent; a gap fill replaces them. */
    val ADMIN: Set<String> = setOf(HEARTBEAT, TEST_REQUEST, RESEND_REQUEST, REJECT, SEQUENCE_RESET, LOGOUT, LOGON)
}
