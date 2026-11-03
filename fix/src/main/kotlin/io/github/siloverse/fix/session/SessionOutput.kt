package io.github.siloverse.fix.session

import io.github.siloverse.fix.message.FixMessage

/**
 * What one call into [FixSession] produced. The driver writes [bytesToSend] to the
 * transport in order, hands [applicationMessages] to the application, and acts on
 * [events]. Nothing here has happened yet when it is returned: the session has only
 * decided.
 */
data class SessionOutput(
    val bytesToSend: List<ByteArray> = emptyList(),
    val applicationMessages: List<FixMessage> = emptyList(),
    val events: List<SessionEvent> = emptyList(),
) {
    operator fun plus(other: SessionOutput) = SessionOutput(
        bytesToSend + other.bytesToSend,
        applicationMessages + other.applicationMessages,
        events + other.events,
    )

    companion object {
        val NONE = SessionOutput()
    }
}
