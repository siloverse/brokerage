package io.github.siloverse.fix.session

/** Which side of the session this is. The initiator connects and sends the first Logon. */
enum class SessionRole { INITIATOR, ACCEPTOR }
