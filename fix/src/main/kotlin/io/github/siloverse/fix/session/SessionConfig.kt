package io.github.siloverse.fix.session

import java.time.Duration

/**
 * Everything a session knows before the first connection.
 *
 * @property heartBtInt the heartbeat interval this side proposes (initiator) or imposes (acceptor).
 * @property resetOnLogon initiator only: send 141=Y on Logon, taking both sides back to 1.
 * @property logonTimeout how long to wait for the peer's Logon before giving up the connection.
 */
data class SessionConfig(
    val senderCompId: String,
    val targetCompId: String,
    val role: SessionRole,
    val heartBtInt: Duration = Duration.ofSeconds(30),
    val resetOnLogon: Boolean = false,
    val logonTimeout: Duration = Duration.ofSeconds(10),
)
