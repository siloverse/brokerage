package io.github.siloverse.fix.session

/** The session's connection state. Sequence numbers live in the store and outlive all of these. */
enum class SessionState {
    /** No transport. Counters are kept; the next connect continues them. */
    DISCONNECTED,
    /** Initiator: Logon sent, waiting for the peer's Logon. */
    LOGON_SENT,
    /** Acceptor: transport open, waiting for the peer's Logon. */
    AWAITING_LOGON,
    /** Both Logons exchanged; application messages may flow. */
    ACTIVE,
    /** Logout sent, waiting for the peer's Logout before dropping the transport. */
    LOGOUT_SENT,
}
